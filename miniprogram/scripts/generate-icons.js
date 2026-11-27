// Lucide 0.468.0, matching the desktop app. See design/ICONS-LICENSE.
// Optional argument: a node_modules folder containing sharp.
const fs = require('node:fs')
const path = require('node:path')
const sharp = require(require.resolve('sharp', { paths: [process.argv[2] || __dirname] }))
const icons = require('../design/icons.json')
const destination = path.resolve(__dirname, '../app/assets/icons')
const colors = { active: '#245b48', muted: '#8b968e', white: '#ffffff' }
async function generate() {
  fs.mkdirSync(destination, { recursive: true })
  for (const [name, shapes] of Object.entries(icons)) {
    for (const [tone, color] of Object.entries(colors)) {
      const body = shapes.map(([tag, attributes]) => '<' + tag + ' ' + Object.entries(attributes)
        .map(([key, value]) => key + '="' + value + '"').join(' ') + '/>').join('')
      const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="96" height="96" fill="none" stroke="' + color +
        '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + body + '</svg>'
      await sharp(Buffer.from(svg)).png().toFile(path.join(destination, name + '-' + tone + '.png'))
    }
  }
  console.log('Generated local icons in ' + destination)
}
generate().catch(error => { console.error(error); process.exitCode = 1 })
