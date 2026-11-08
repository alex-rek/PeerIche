# Code provenance

PeerIche is a continuation of PeerTubeTV by the same author, Arctic Fox Apps.

The initial independent implementation was informed by the PeerTubeTV audit. PeerTube API data shapes, use of Sepia Search, the idea of combining results from multiple search sources, and playback stream selection were reviewed in `PeerTubeTV/app/src/main/java/app/peertube/tv/PeerTubeApi.kt` and `SearchDiscovery.kt`. PeerIche's initial network and UI code is newly organized in its own package and does not depend on or modify the PeerTubeTV checkout.

Third-party libraries keep their upstream copyright and license terms. Inventory direct and transitive dependencies before a distributable release.

Phone broadcasting uses [RootEncoder 2.5.4](https://github.com/pedroSG94/RootEncoder/tree/2.5.4), copyright pedroSG94, under Apache-2.0. The bitmap SurfaceTexture source was informed by its BitmapSource example, with custom aspect-preserving drawing and lifecycle handling. The license is included in app/src/main/assets/licenses/RootEncoder-Apache-2.0.txt. This version matches the project's Kotlin 2.0.21; the added library resolves kotlinx.coroutines 1.9.0. Existing Media3, Compose, Kotlin and Android SDK versions are unchanged.
