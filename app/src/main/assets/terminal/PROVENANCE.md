# Terminal asset provenance

The bundled terminal renderer comes from the MIT-licensed xterm.js project.
The files match the published npm packages byte for byte:

| Local file | npm package and path | SHA-256 |
| --- | --- | --- |
| `xterm.js` | `@xterm/xterm@5.5.0`, `package/lib/xterm.js` | `1f991ac3b4b283ebf96e60ae23a00a52765dd3a2e46fa6fdda9f1aab032f7495` |
| `xterm.css` | `@xterm/xterm@5.5.0`, `package/css/xterm.css` | `ba8e6985669488981ccf40c0cefe3aba80722cb6c92de7ad628b0bd717faf2b6` |
| `addon-fit.js` | `@xterm/addon-fit@0.10.0`, `package/lib/addon-fit.js` | `bdaefa370b1bfc42ee88d46fe6072400902a4d4b2d45cd93438dda9b23c97089` |

Package archives (including the TypeScript sources) are available at
`https://registry.npmjs.org/@xterm/xterm/-/xterm-5.5.0.tgz` and
`https://registry.npmjs.org/@xterm/addon-fit/-/addon-fit-0.10.0.tgz`.
The local license copies are `XTERM-LICENSE` and `ADDON-FIT-LICENSE`.
`terminal.js`, `terminal.css`, and `index.html` are androidKVM source files.
