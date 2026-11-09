"""One-time extraction of legacy bilingual Compose labels into Android resources."""
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parents[1]
PAIR = re.compile(r'uiText\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)', re.S)
CONDITIONAL = re.compile(r'uiText\(if \(([^)]+)\) "((?:[^"\\]|\\.)*)" else "((?:[^"\\]|\\.)*)", if \(\1\) "((?:[^"\\]|\\.)*)" else "((?:[^"\\]|\\.)*)"\)')


def android_text(value):
    return '"' + escape(value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\t', '\\t')) + '"'


def main():
    rows = {}
    def register(ru, en):
        key = 'ui_' + re.sub('[^a-z0-9]+', '_', en.lower()).strip('_')[:42].rstrip('_') + '_' + hashlib.sha1((ru + '|' + en).encode()).hexdigest()[:6]
        rows[key] = (ru, en)
        return 'R.string.' + key
    for path in sorted((ROOT / 'app/src/main/java').rglob('*.kt')):
        text = path.read_text(encoding='utf-8')

        def replace(match):
            if '$' in match[1] or '$' in match[2]:
                return match[0]
            ru, en = [json.loads('"' + match[i] + '"') for i in (1, 2)]
            return 'uiText(' + register(ru, en) + ')'

        result = PAIR.sub(replace, text)
        def conditional(match):
            values = [json.loads('"' + match[i] + '"') for i in (2, 3, 4, 5)]
            return 'uiText(if (' + match[1] + ') ' + register(values[0], values[2]) + ' else ' + register(values[1], values[3]) + ')'
        result = CONDITIONAL.sub(conditional, result)
        if result != text:
            path.write_text(result, encoding='utf-8')
    for locale, index in [('values', 1), ('values-ru', 0)]:
        path = ROOT / 'app/src/main/res' / locale / 'interface.xml'
        if rows:
            existing = path.read_text(encoding='utf-8') if path.exists() else '<resources>\n</resources>\n'
            entries = [f'    <string name="{key}" formatted="false">{android_text(pair[index])}</string>' for key, pair in sorted(rows.items()) if f'name="{key}"' not in existing]
            path.write_text(existing.replace('</resources>', '\n'.join(entries) + '\n</resources>'), encoding='utf-8')
    print(f'Migrated {len(rows)} distinct bilingual labels')


if __name__ == '__main__':
    main()
