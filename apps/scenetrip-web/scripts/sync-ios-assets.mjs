// iOS 앱의 문구표와 그림을 웹으로 옮긴다 (읽기만 한다 — iOS 소스는 고치지 않는다).
//  - en.lproj/Localizable.strings → src/i18n/en.generated.json  (열쇠는 한국어 원문)
//  - Images.xcassets 의 해태 그림 → public/generated/*.png
import { copyFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { GEN_OUT, IOS_ROOT } from './paths.mjs'

function unescapeString(s) {
  return s.replace(/\\(["\\nt])/g, (_, c) => (c === 'n' ? '\n' : c === 't' ? '\t' : c))
}

/** `"키" = "값";` 항목들을 읽는다. 문자열 안의 \" \n \\ 를 풀고 블록 주석은 건너뛴다. */
export function parseStrings(text) {
  const withoutComments = text.replace(/\/\*[\s\S]*?\*\//g, '')
  const out = {}
  const re = /"((?:[^"\\]|\\.)*)"\s*=\s*"((?:[^"\\]|\\.)*)"\s*;/g
  for (const m of withoutComments.matchAll(re)) out[unescapeString(m[1])] = unescapeString(m[2])
  return out
}

const stringsPath = resolve(IOS_ROOT, 'resources/en.lproj/Localizable.strings')
const strings = parseStrings(readFileSync(stringsPath, 'utf8'))
mkdirSync(resolve(GEN_OUT, 'src/i18n'), { recursive: true })
writeFileSync(resolve(GEN_OUT, 'src/i18n/en.generated.json'), JSON.stringify(strings, null, 1))

const publicDir = resolve(GEN_OUT, 'public/generated')
mkdirSync(publicDir, { recursive: true })
const images = resolve(IOS_ROOT, 'resources/Images.xcassets')
for (const set of readdirSync(images).filter((n) => n.endsWith('.imageset'))) {
  for (const file of readdirSync(resolve(images, set))) {
    if (/\.(png|svg|jpg)$/.test(file)) copyFileSync(resolve(images, set, file), resolve(publicDir, file))
  }
}
console.log(`ios assets: ${Object.keys(strings).length} strings, images -> public/generated`)
