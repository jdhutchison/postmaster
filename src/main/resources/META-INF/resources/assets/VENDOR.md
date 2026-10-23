# Vendored assets

Everything the UI loads is served from these files; nothing is fetched from a CDN at runtime.
Each package was downloaded from the npm registry and its tarball checked against the registry's
sha512 integrity value before extraction.

| File | Package | Version | Licence | SHA-256 |
|---|---|---|---|---|
| `vendor/htmx/htmx.min.js` | [htmx.org](https://www.npmjs.com/package/htmx.org) (`dist/htmx.min.js`) | 2.0.11 | 0BSD (`vendor/htmx/LICENSE`) | `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717` |
| `fonts/atkinson-hyperlegible-next/atkinson-hyperlegible-next-latin-ext-wght-italic.woff2` | [@fontsource-variable/atkinson-hyperlegible-next](https://www.npmjs.com/package/@fontsource-variable/atkinson-hyperlegible-next) (`files/atkinson-hyperlegible-next-latin-ext-wght-italic.woff2`) | 5.3.0 | SIL OFL 1.1 (`fonts/atkinson-hyperlegible-next/OFL.txt`) | `c0c8da569118e5b2c17032c4f1d0e293271e478fb50d869aefd08fcffdf2042b` |
| `fonts/atkinson-hyperlegible-next/atkinson-hyperlegible-next-latin-ext-wght-normal.woff2` | [@fontsource-variable/atkinson-hyperlegible-next](https://www.npmjs.com/package/@fontsource-variable/atkinson-hyperlegible-next) (`files/atkinson-hyperlegible-next-latin-ext-wght-normal.woff2`) | 5.3.0 | SIL OFL 1.1 (`fonts/atkinson-hyperlegible-next/OFL.txt`) | `7dae0c6c66af1aec82e096186aeb1f0e6fa36ab8061ed65422f2b7daf4dd93f3` |
| `fonts/atkinson-hyperlegible-next/atkinson-hyperlegible-next-latin-wght-italic.woff2` | [@fontsource-variable/atkinson-hyperlegible-next](https://www.npmjs.com/package/@fontsource-variable/atkinson-hyperlegible-next) (`files/atkinson-hyperlegible-next-latin-wght-italic.woff2`) | 5.3.0 | SIL OFL 1.1 (`fonts/atkinson-hyperlegible-next/OFL.txt`) | `4a5037bfaf6680f40147407407ec09fa42925774bde809a579283d27f9f08106` |
| `fonts/atkinson-hyperlegible-next/atkinson-hyperlegible-next-latin-wght-normal.woff2` | [@fontsource-variable/atkinson-hyperlegible-next](https://www.npmjs.com/package/@fontsource-variable/atkinson-hyperlegible-next) (`files/atkinson-hyperlegible-next-latin-wght-normal.woff2`) | 5.3.0 | SIL OFL 1.1 (`fonts/atkinson-hyperlegible-next/OFL.txt`) | `18b2a1a39a2fa298b0ba5390aca68462669826c90925656f1c1f6796e0e1bbaf` |

The font is Atkinson Hyperlegible Next by the Braille Institute, https://github.com/googlefonts/atkinson-hyperlegible-next.
The `@font-face` rules in `css/postmaster.css` follow the package's `wght.css` and `wght-italic.css`, including their `unicode-range` values.
