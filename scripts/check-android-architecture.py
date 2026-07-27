from pathlib import Path
import re, sys
root=Path(__file__).resolve().parents[1] if 'scripts' in Path(__file__).parts else Path('.')
source=root/'app/src/main/java'
over=[]
for p in source.rglob('*.kt'):
 n=len(p.read_text(encoding='utf-8-sig').splitlines())
 if n>2200: over.append(f'{p.relative_to(root)}: {n} lines (>2200)')
forbidden={
 'content.take(': 'prompt/content tail',
 'fileName=$': 'filename log interpolation',
 'url=$': 'URL log interpolation',
 'path=$': 'path log interpolation',
 'lastTail=': 'message tail log',
}
logs=[]
for p in source.rglob('*.kt'):
 for i,line in enumerate(p.read_text(encoding='utf-8-sig').splitlines(),1):
  if 'Log.' not in line and 'debugLog' not in line: continue
  for needle,label in forbidden.items():
   if needle in line: logs.append(f'{p.relative_to(root)}:{i}: {label}')
errors=over+logs
if errors:
 print('\n'.join(errors),file=sys.stderr); sys.exit(1)
print(f'architecture check passed: {sum(1 for _ in source.rglob("*.kt"))} production Kotlin files')
