package main

const appCSS = `
:root{color-scheme:light dark;--primary:#087e79;--accent:#ccf3ea;--bg:#f6fbfa;--card:#ffffff;--ink:#173633;--muted:#52746f;--border:#cde4dd;--shadow:0 10px 32px #0e514018;font-family:system-ui,-apple-system,"Noto Sans TC",sans-serif;font-size:16px}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);line-height:1.5}button,input,select,textarea{font:inherit}
button{min-height:48px;padding:.65rem 1rem;border:0;border-radius:14px;background:var(--primary);color:white;font-weight:700;cursor:pointer}
button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:3px solid #d59e30;outline-offset:2px}
button:disabled{opacity:.48;cursor:wait}button.ghost{border:1px solid var(--border);color:var(--primary);background:var(--card)}
.shell{max-width:1080px;margin:0 auto;padding:0 16px 92px}header{display:flex;justify-content:space-between;align-items:center;gap:14px;padding:24px 0 18px;border-bottom:1px solid var(--border)}
.brand{display:flex;align-items:center;gap:12px}.brand strong{display:block;font-size:1.4rem}.brand small{display:block;color:var(--muted)}.mark{display:grid;place-items:center;width:45px;height:45px;border-radius:14px;background:var(--primary);color:white;font-size:1.7rem}
h1{font-size:clamp(1.4rem,4vw,2rem);letter-spacing:-.02em;margin:0 0 6px}h2{font-size:1.15rem;margin:0 0 14px}.heading{margin:24px 0}.heading p{margin:0;color:var(--muted)}
.card{background:var(--card);border:1px solid var(--border);box-shadow:var(--shadow);border-radius:22px;padding:clamp(16px,3vw,26px);margin-bottom:16px}
.columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.columns .card{margin:0 0 16px}
form{display:grid;gap:12px;align-items:start}label{display:grid;gap:5px;font-size:.91rem;font-weight:650}
input,select,textarea{width:100%;min-height:48px;background:var(--bg);color:var(--ink);border:1px solid var(--border);border-radius:12px;padding:10px 12px;min-width:0}
textarea{resize:vertical;min-height:92px}hr{border:0;border-top:1px solid var(--border);margin:24px 0}.chat{display:flex;flex-direction:column;gap:12px;min-height:220px;max-height:55vh;overflow:auto;padding:16px 0;margin:8px 0}
.bubble{max-width:min(100%,78ch);border:1px solid var(--border);background:var(--bg);padding:12px 14px;border-radius:15px;overflow-wrap:anywhere;white-space:pre-wrap}
.bubble.user{margin-left:auto;background:var(--accent);color:#124741}.bubble.assistant{margin-right:auto}.empty{color:var(--muted);text-align:center;margin:auto}
.cards{display:grid;gap:10px}.item{border:1px solid var(--border);border-radius:12px;padding:12px 14px;overflow-wrap:anywhere}.item strong{display:block}.item small{color:var(--muted)}
pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:.8rem;line-height:1.6}#notice{min-height:1.5rem;color:var(--primary);font-size:.91rem;font-weight:600}
.bottom-nav{position:fixed;left:50%;bottom:0;transform:translateX(-50%);width:min(100%,1080px);display:flex;justify-content:space-around;gap:8px;padding:10px max(12px,env(safe-area-inset-right)) calc(10px + env(safe-area-inset-bottom));border-top:1px solid var(--border);background:var(--card);box-shadow:0 -6px 25px #0e51401a;z-index:5}
.bottom-nav button{flex:1;background:transparent;color:var(--muted);border-radius:12px}.bottom-nav button.active{background:var(--accent);color:#075c59}
[hidden]{display:none!important}@media(max-width:690px){.columns{grid-template-columns:1fr}.shell{padding:0 12px 100px}header{padding:12px 0}.card{border-radius:18px}button{touch-action:manipulation}}
@media(prefers-color-scheme:dark){:root{--primary:#56beb2;--accent:#214943;--bg:#102422;--card:#18322f;--ink:#e3f3ed;--muted:#acd1c5;--border:#365b53;--shadow:0 10px 34px #0004}button{color:#082926}.bubble.user{color:#dbf7e7}.bottom-nav button.active{color:#dcfff4}}
@media(prefers-reduced-motion:no-preference){button{transition:background-color .18s ease,transform .18s ease}button:active{transform:scale(.98)}}
`
