# Terminal asset provenance

The bundled terminal renderer comes from the MIT-licensed xterm.js project.
The files match the published npm packages byte for byte:

| Local file | npm package and path | SHA-256 |
| --- | --- | --- |
| `xterm.js` | `@xterm/xterm@6.0.0`, `package/lib/xterm.js` | `14903579ff54664cd72f8e8699e6961a6272c21863ec1c3b118cdc8af5d4a972` |
| `xterm.css` | `@xterm/xterm@6.0.0`, `package/css/xterm.css` | `854a7c0fb70e8b1a083c16797ab827299fb18744f5ad34f227b48337e33293c6` |
| `addon-fit.js` | `@xterm/addon-fit@0.11.0`, `package/lib/addon-fit.js` | `ba3ea256ce0620a0992a197d6c9baea64823fc93d8da07a9e366ca9943c18527` |

Package archives (including the TypeScript sources) are available at
`https://registry.npmjs.org/@xterm/xterm/-/xterm-6.0.0.tgz` and
`https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.11.0.tgz`.
The local license copies are `XTERM-LICENSE` and `ADDON-FIT-LICENSE`.
`terminal.js`, `terminal.css`, and `index.html` are androidKVM source files.
