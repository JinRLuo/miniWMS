const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '../app')
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? walk(path.join(dir, entry.name)) : [path.join(dir, entry.name)])
}
const files = walk(root)
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
let count = 0
for (const file of files) {
  if (file.endsWith('.json')) JSON.parse(fs.readFileSync(file, 'utf8'))
  if (file.endsWith('.js')) {
    const source = fs.readFileSync(file, 'utf8')
    new vm.Script(source, { filename: file })
    for (const match of source.matchAll(/require\(['"]([^'"]+)['"]\)/g)) {
      assert.ok(match[1].startsWith('.'), 'Mini program cannot import Node dependencies: ' + file)
      assert.ok(fs.existsSync(path.resolve(path.dirname(file), match[1] + '.js')), 'Missing dependency in ' + file)
    }
  }
  count++
}
for (const page of app.pages.concat(['custom-tab-bar/index', 'components/movement-list/index'])) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(root, page + '.' + extension)), 'Missing ' + page + '.' + extension)
  const source = fs.readFileSync(path.join(root, page + '.wxml'), 'utf8')
  const script = fs.readFileSync(path.join(root, page + '.js'), 'utf8')
  for (const match of source.matchAll(/src="(\/[^"{}]+)"/g)) {
    assert.ok(fs.existsSync(path.join(root, match[1])), 'Missing local image: ' + match[1])
  }
  for (const match of source.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    new vm.Script('(' + match[1] + ')', { filename: page + '.wxml expression' })
  }
  for (const match of source.matchAll(/(?:bind|catch)(?::)?(?:tap|input|change|confirm|getphonenumber)="([a-zA-Z]+)"/g)) {
    assert.ok(new RegExp('\\b' + match[1] + '\\s*\\(').test(script), 'Missing event handler ' + page + ': ' + match[1])
  }
}
for (const tab of app.tabBar.list) assert.ok(app.pages.includes(tab.pagePath), 'Unregistered tab page')
assert.equal(app.tabBar.custom, true)
console.log('Checked ' + count + ' mini program files, page registration, dependencies and event handlers.')
