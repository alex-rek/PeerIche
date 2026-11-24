"""Validate the reviewed interface catalogs in tools/interface-translations and write Android locale resources.

Each tools/interface-translations/<language>.tsv holds one `key<TAB>text` line per string from the English
resources in app/src/main/res/values. Leading and trailing spaces are taken from the English source, so the
catalogs can be edited without invisible whitespace. Run without arguments to regenerate values-<lang>/strings.xml.
"""
import json
import pathlib
import re
import xml.etree.ElementTree as ET
from migrate_interface_strings import android_text

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOGS = ROOT / 'tools/interface-translations'
LANGUAGES = ('es', 'fr', 'de', 'pt', 'tr', 'uk', 'zh', 'ar', 'he')
PLACEHOLDER = re.compile(r'%\d+\$[sd]')
# Strings kept in their own resource files and translated there.
SEPARATE_FILES = ('broadcast_logo', 'creation_updates', 'creator_schedule', 'hidden_channels', 'network_proxy',
                  'home_interests', 'home_languages', 'privacy_settings', 'server_catalog', 'video_library')


def source_catalog():
    catalog = {}
    for path in sorted((ROOT / 'app/src/main/res/values').glob('*.xml')):
        for item in ET.parse(path).getroot().findall('string'):
            text = item.text or ''
            if text.startswith('"') and text.endswith('"'):
                text = json.loads(text)
            catalog[item.attrib['name']] = text
    return catalog


def separate_keys():
    keys = set()
    for name in SEPARATE_FILES:
        path = ROOT / f'app/src/main/res/values/{name}.xml'
        keys |= {item.attrib['name'] for item in ET.parse(path).getroot().findall('string')}
    return keys


def read_catalog(language):
    translations = {}
    for number, line in enumerate((CATALOGS / f'{language}.tsv').read_text(encoding='utf-8').splitlines(), 1):
        if not line.strip():
            continue
        key, separator, text = line.partition('\t')
        if not separator or '\t' in text:
            raise ValueError(f'{language}.tsv:{number}: expected one tab between key and text')
        if key in translations:
            raise ValueError(f'{language}.tsv:{number}: duplicate {key}')
        translations[key] = text.replace('\\n', '\n')
    return translations


def main():
    source = source_catalog()
    expected = set(source) - {'app_name'} - separate_keys()
    for language in LANGUAGES:
        translations = read_catalog(language)
        if set(translations) != expected:
            raise ValueError(f'{language}: missing {sorted(expected - set(translations))}, unexpected {sorted(set(translations) - expected)}')
        for key, value in translations.items():
            raw = source[key]
            value = value.strip()
            if not value:
                raise ValueError(f'{language}: empty {key}')
            translations[key] = raw[:len(raw) - len(raw.lstrip())] + value + raw[len(raw.rstrip()):]
            if sorted(PLACEHOLDER.findall(translations[key])) != sorted(PLACEHOLDER.findall(raw)):
                raise ValueError(f'{language}: invalid placeholders for {key}')
            for brand in ('PeerTube', 'PeerIche'):
                if brand in raw and brand not in translations[key]:
                    raise ValueError(f'{language}: {brand} must stay untranslated in {key}')
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
