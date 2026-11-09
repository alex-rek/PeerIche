"""Check interface locale coverage, locale declarations and format arguments."""
import json
import pathlib
import re
import xml.etree.ElementTree as ET
from import_interface_translations import source_catalog, PLACEHOLDER

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXPECTED = {'en', 'ru', 'es', 'fr', 'de', 'pt', 'tr', 'uk', 'zh', 'ar', 'he'}


def load(folder):
    strings = {}
    for path in sorted(folder.glob('*.xml')):
        for item in ET.parse(path).getroot().findall('string'):
            key = item.attrib['name']
            if key in strings:
                raise ValueError(f'Duplicate string: {key}')
            text = item.text or ''
            if text.startswith('"') and text.endswith('"'):
                text = json.loads(text)
            strings[key] = text
    return strings


def main():
    source = source_catalog()
    required = set(source) - {'app_name'}
    declared = {item.attrib['{http://schemas.android.com/apk/res/android}name'] for item in ET.parse(ROOT / 'app/src/main/res/xml/locales_config.xml').getroot()}
    choices = set(re.findall(r'"([a-z]{2})" to ', (ROOT / 'app/src/main/java/com/arcticfoxapps/peeriche/InterfaceLanguages.kt').read_text(encoding='utf-8')))
    assert declared == choices == EXPECTED
    for locale in sorted(EXPECTED - {'en'}):
        translated = load(ROOT / f'app/src/main/res/values-{"iw" if locale == "he" else locale}')
        assert required <= translated.keys(), f'{locale}: missing {required - translated.keys()}'
        assert set(translated) <= set(source), f'{locale}: extra strings'
        for key in required:
            assert translated[key].strip(), f'{locale}: empty {key}'
            assert sorted(PLACEHOLDER.findall(translated[key])) == sorted(PLACEHOLDER.findall(source[key])), f'{locale}: placeholders in {key}'
        print(f'{locale}: {len(required)} interface strings verified')
    for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
        assert not re.search(r'uiText\(\s*"', path.read_text(encoding='utf-8')), f'Legacy bilingual text: {path}'


if __name__ == '__main__':
    main()
