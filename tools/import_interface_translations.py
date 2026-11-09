"""Validate exported static catalogs and write Android locale resources."""
import argparse
import json
import pathlib
import re
import xml.etree.ElementTree as ET
from migrate_interface_strings import android_text

ROOT = pathlib.Path(__file__).resolve().parents[1]
LANGUAGES = ('es', 'fr', 'de', 'pt', 'tr', 'uk', 'zh', 'ar', 'he')
PLACEHOLDER = re.compile(r'%\d+\$[sd]')


def source_catalog():
    catalog = {}
    for path in sorted((ROOT / 'app/src/main/res/values').glob('*.xml')):
        for item in ET.parse(path).getroot().findall('string'):
            text = item.text or ''
            if text.startswith('"') and text.endswith('"'):
                text = json.loads(text)
            catalog[item.attrib['name']] = text
    return catalog


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=pathlib.Path)
    args = parser.parse_args()
    source = source_catalog()
    overrides = json.loads((ROOT / 'tools/interface_translation_overrides.json').read_text(encoding='utf-8'))
    key_overrides = json.loads((ROOT / 'tools/interface_translation_key_overrides.json').read_text(encoding='utf-8'))
    extra_overrides = json.loads((ROOT / 'tools/interface_translation_zh_ar_he.json').read_text(encoding='utf-8'))
    expected = set(source) - {'app_name'}
    for language in LANGUAGES:
        translations = json.loads((args.directory / f'{language}.json').read_text(encoding='utf-8-sig'))
        for key in translations:
            if language in ('zh', 'ar', 'he') and source.get(key, '').strip() in extra_overrides:
                raw = source[key]
                translations[key] = raw[:len(raw) - len(raw.lstrip())] + extra_overrides[raw.strip()][('zh', 'ar', 'he').index(language)] + raw[len(raw.rstrip()):]
            if source.get(key, '').strip() in overrides and LANGUAGES.index(language) < len(overrides[source[key].strip()]):
                raw = source[key]
                translations[key] = raw[:len(raw) - len(raw.lstrip())] + overrides[raw.strip()][LANGUAGES.index(language)] + raw[len(raw.rstrip()):]
            if key in key_overrides and LANGUAGES.index(language) < len(key_overrides[key]):
                translations[key] = key_overrides[key][LANGUAGES.index(language)]
            translations[key] = re.sub('peeriche', 'PeerIche', translations[key], flags=re.I)
            translations[key] = re.sub('peertube', 'PeerTube', translations[key], flags=re.I)
        if set(translations) != expected:
            raise ValueError(f'{language}: missing {sorted(expected - set(translations))}, unexpected {sorted(set(translations) - expected)}')
        for key, value in translations.items():
            if not value.strip() and source[key].strip():
                raise ValueError(f'{language}: empty {key}')
            if sorted(PLACEHOLDER.findall(value)) != sorted(PLACEHOLDER.findall(source[key])):
                raise ValueError(f'{language}: invalid placeholders for {key}')
        folder = ROOT / f'app/src/main/res/values-{"iw" if language == "he" else language}'
        folder.mkdir(exist_ok=True)
        lines = ['<resources>']
        for key, value in sorted(translations.items()):
            formatted = '' if PLACEHOLDER.search(source[key]) else ' formatted="false"'
            lines.append(f'    <string name="{key}"{formatted}>{android_text(value)}</string>')
        lines.append('</resources>')
        (folder / 'strings.xml').write_text('\n'.join(lines) + '\n', encoding='utf-8')
        print(f'{language}: {len(translations)} strings, placeholders valid')


if __name__ == '__main__':
    main()
