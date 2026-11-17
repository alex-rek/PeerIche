param([Parameter(Mandatory=$true)][string]$SourceDirectory,
      [string]$ResourceDirectory = "$PSScriptRoot/../app/src/main/res")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$drawingReferences = @([AppContext]::GetData('TRUSTED_PLATFORM_ASSEMBLIES').Split([IO.Path]::PathSeparator)) + @([Drawing.Bitmap].Assembly.Location, [Drawing.Color].Assembly.Location)
Add-Type -ReferencedAssemblies ($drawingReferences | Select-Object -Unique) -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
public static class MobileBrandAssets {
    static Rectangle Bounds(Bitmap image) {
        int left=image.Width, top=image.Height, right=0, bottom=0;
        for(int y=0;y<image.Height;y++) for(int x=0;x<image.Width;x++) {
            if(image.GetPixel(x,y).A < 24) continue;
            left=Math.Min(left,x); top=Math.Min(top,y); right=Math.Max(right,x); bottom=Math.Max(bottom,y);
        }
        return Rectangle.FromLTRB(left,top,right+1,bottom+1);
    }
    public static void ResizeIcon(string input,string output,int size,int artworkSize) {
        using(var source=new Bitmap(input)) Resize(source,Bounds(source),output,size,artworkSize);
    }
    static void Resize(Bitmap source,Rectangle crop,string output,int size,int artworkSize) {
        using(var result=new Bitmap(size,size,PixelFormat.Format32bppArgb)) {
            using(var g=Graphics.FromImage(result)) {
                g.Clear(Color.Transparent); g.CompositingQuality=CompositingQuality.HighQuality;
                g.InterpolationMode=InterpolationMode.HighQualityBicubic; g.PixelOffsetMode=PixelOffsetMode.HighQuality;
                float scale=(float)artworkSize/Math.Max(crop.Width,crop.Height);
                float w=crop.Width*scale,h=crop.Height*scale;
                g.DrawImage(source,new RectangleF((size-w)/2,(size-h)/2,w,h),crop,GraphicsUnit.Pixel);
            }
            result.Save(output,ImageFormat.Png);
        }
    }
    static int Cross(Point a,Point b,Point c) { return (b.X-a.X)*(c.Y-a.Y)-(b.Y-a.Y)*(c.X-a.X); }
    public static void Monochrome(string input,string output) {
        using(var source=new Bitmap(input)) using(var mask=new Bitmap(source.Width,source.Height,PixelFormat.Format32bppArgb)) {
            // Extract connected parts of the supplied symbol, excluding its dark tile.
            bool[,] seen=new bool[source.Width,source.Height];
            var queue=new Point[source.Width*source.Height*4+3];
            int head=0, tail=0;
            queue[tail++]=new Point(180,180); queue[tail++]=new Point(256,256); queue[tail++]=new Point(320,280);
            while(head<tail) {
                var p=queue[head++];
                if(p.X<0 || p.Y<0 || p.X>=source.Width || p.Y>=source.Height || seen[p.X,p.Y]) continue;
                seen[p.X,p.Y]=true; var c=source.GetPixel(p.X,p.Y);
                if(c.A<32 || Math.Max(c.R,Math.Max(c.G,c.B))<=60) continue;
                mask.SetPixel(p.X,p.Y,Color.White);
                queue[tail++]=new Point(p.X-1,p.Y); queue[tail++]=new Point(p.X+1,p.Y);
                queue[tail++]=new Point(p.X,p.Y-1); queue[tail++]=new Point(p.X,p.Y+1);
            }
            // Trace the supplied symbol's outer silhouette; fill internal colour seams.
            var edge=new Point[source.Height*2]; int count=0;
            for(int y=0;y<mask.Height;y++) {
                int left=mask.Width,right=-1;
                for(int x=0;x<mask.Width;x++) if(mask.GetPixel(x,y).A>0) { left=Math.Min(left,x);right=x; }
                if(right>=left) { edge[count++]=new Point(left,y); edge[count++]=new Point(right,y); }
            }
            for(int i=1;i<count;i++) {
                var point=edge[i]; int j=i-1;
                while(j>=0 && (edge[j].X>point.X || (edge[j].X==point.X && edge[j].Y>point.Y))) { edge[j+1]=edge[j]; j--; }
                edge[j+1]=point;
            }
            var hull=new Point[count*2]; int n=0;
            for(int i=0;i<count;i++) {
                while(n>=2 && Cross(hull[n-2],hull[n-1],edge[i])<=0) n--;
                hull[n++]=edge[i];
            }
            for(int i=count-2,limit=n+1;i>=0;i--) {
                while(n>=limit && Cross(hull[n-2],hull[n-1],edge[i])<=0) n--;
                hull[n++]=edge[i];
            }
            var contour=new Point[n-1]; Array.Copy(hull,contour,n-1);
            using(var g=Graphics.FromImage(mask)) { g.Clear(Color.Transparent); using(var brush=new SolidBrush(Color.White)) g.FillPolygon(brush,contour); }
            Resize(mask,Bounds(source),output,432,248);
        }
    }
}
'@
function New-ResourceDirectory([string]$Name) {
    $directory = Join-Path $ResourceDirectory $Name
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    return $directory
}
$normal = New-ResourceDirectory 'drawable-nodpi'
$night = New-ResourceDirectory 'drawable-night-nodpi'
foreach ($theme in @('light','dark')) {
    $icon = Join-Path $SourceDirectory "PeerIche_icon_$theme.png"
    $logo = Join-Path $SourceDirectory "PeerIche_logo_$theme.png"
    if (!(Test-Path -LiteralPath $icon) -or !(Test-Path -LiteralPath $logo)) { throw 'Required Mobile assets are missing' }
    Copy-Item -LiteralPath $logo -Destination (Join-Path $normal "peeriche_logo_$theme.png")
    $drawables = if ($theme -eq 'light') { $normal } else { $night }
    Copy-Item -LiteralPath $icon -Destination (Join-Path $drawables 'peeriche_icon.png')
    [MobileBrandAssets]::ResizeIcon($icon,(Join-Path $drawables 'peeriche_launcher_foreground.png'),432,248)
    foreach ($entry in @(@('mdpi',48),@('hdpi',72),@('xhdpi',96),@('xxhdpi',144),@('xxxhdpi',192))) {
        $folder = if ($theme -eq 'light') { "mipmap-$($entry[0])" } else { "mipmap-night-$($entry[0])" }
        $directory = New-ResourceDirectory $folder
        [MobileBrandAssets]::ResizeIcon($icon,(Join-Path $directory 'ic_launcher.png'),$entry[1],[int]($entry[1]*0.88))
    }
}
[MobileBrandAssets]::Monochrome((Join-Path $SourceDirectory 'PeerIche_icon_dark.png'),(Join-Path $normal 'peeriche_launcher_monochrome.png'))
