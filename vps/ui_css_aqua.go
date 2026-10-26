package main

// The optional appearance uses the same controls, scrollers and state owners.
// Opaque reading surfaces remain usable without backdrop-filter support.
const aquaCSS = `
:root[data-interface="aqua"]{--primary:#17648d;--on-primary:#fff;--accent:#dceef8;--bg:#eaf2f7;--card:rgba(255,255,255,.91);--surface:rgba(237,246,252,.92);--ink:#213850;--muted:#516981;--border:rgba(120,156,184,.3);--shadow:0 10px 32px rgba(35,65,96,.08),inset 0 1px 0 rgba(255,255,255,.8);--radius:22px;--aqua-glass:linear-gradient(130deg,rgba(255,255,255,.92),rgba(241,249,255,.86));--aqua-fog:linear-gradient(180deg,rgba(240,248,255,.16),rgba(233,244,252,.66) 50%,rgba(231,242,250,.92));--aqua-line:rgba(255,255,255,.8)}
:root[data-interface="aqua"][data-accent="violet"]{--primary:#67458e;--accent:#eee6f8}:root[data-interface="aqua"][data-accent="indigo"]{--primary:#41588f;--accent:#e7ecfc}
:root[data-interface="aqua"][data-theme="dark"]{--primary:#9adaf3;--on-primary:#153349;--accent:#29475e;--bg:#142436;--card:rgba(25,43,63,.94);--surface:rgba(32,53,76,.95);--ink:#edf5ff;--muted:#b6cbe0;--border:rgba(169,203,230,.23);--shadow:0 12px 36px rgba(4,13,27,.2),inset 0 1px 0 rgba(214,237,255,.09);--aqua-glass:linear-gradient(130deg,rgba(28,47,70,.94),rgba(24,43,64,.9));--aqua-fog:linear-gradient(180deg,rgba(14,29,48,.5),rgba(17,33,52,.78) 45%,rgba(17,33,52,.96));--aqua-line:rgba(179,211,240,.24)}
:root[data-interface="aqua"][data-theme="dark"][data-accent="violet"]{--primary:#d5c0f5;--accent:#493959}:root[data-interface="aqua"][data-theme="dark"][data-accent="indigo"]{--primary:#c4d0ff;--accent:#33486b}
@media(prefers-color-scheme:dark){
 :root[data-interface="aqua"]:not([data-theme="light"]){--primary:#9adaf3;--on-primary:#153349;--accent:#29475e;--bg:#142436;--card:rgba(25,43,63,.94);--surface:rgba(32,53,76,.95);--ink:#edf5ff;--muted:#b6cbe0;--border:rgba(169,203,230,.23);--shadow:0 12px 36px rgba(4,13,27,.2),inset 0 1px 0 rgba(214,237,255,.09);--aqua-glass:linear-gradient(130deg,rgba(28,47,70,.94),rgba(24,43,64,.9));--aqua-fog:linear-gradient(180deg,rgba(14,29,48,.5),rgba(17,33,52,.78) 45%,rgba(17,33,52,.96));--aqua-line:rgba(179,211,240,.24)}
 :root[data-interface="aqua"][data-accent="violet"]:not([data-theme="light"]){--primary:#d5c0f5;--accent:#493959}:root[data-interface="aqua"][data-accent="indigo"]:not([data-theme="light"]){--primary:#c4d0ff;--accent:#33486b}
}
:root[data-interface="aqua"] body{isolation:isolate;min-height:100dvh;background:var(--bg)}
:root[data-interface="aqua"] body::before{content:"";position:fixed;inset:0;z-index:-1;pointer-events:none;background:var(--aqua-fog),url("/assets/aqua-garden-v1.webp") 35% 25% / cover no-repeat}
:root[data-interface="aqua"] :is(.card,.provider-account-card,.plot-card,.checkin-summary,.connection-speed-card,.comparison-results>.detail-section){background:var(--aqua-glass);border-color:var(--aqua-line);border-radius:22px;box-shadow:var(--shadow)}
:root[data-interface="aqua"] :is(header,.bottom-nav,.desktop-sidebar){background:var(--aqua-glass);border:1px solid var(--aqua-line);box-shadow:var(--shadow);backdrop-filter:blur(24px) saturate(1.2);-webkit-backdrop-filter:blur(24px) saturate(1.2)}
:root[data-interface="aqua"] button{border-radius:13px}
:root[data-interface="aqua"] :is(input,select,textarea){border-radius:12px}
:root[data-interface="aqua"] .mark{border:1px solid var(--aqua-line);border-radius:12px;background:linear-gradient(135deg,#a4e7f4,#257ea9);color:#fff;box-shadow:inset 0 1px 0 #fff8,0 4px 12px #286c9126}
:root[data-interface="aqua"] .brand strong{color:var(--ink)}
:root[data-interface="aqua"] .brand small{display:block;font-size:.65rem;color:var(--muted);letter-spacing:.18em}
:root[data-interface="aqua"] .workspace-tabs{border-color:var(--border);gap:6px}
:root[data-interface="aqua"] .workspace-tabs>button[aria-selected="true"],:root[data-interface="aqua"] .bottom-nav button.active{background:var(--accent);color:var(--primary);box-shadow:inset 0 1px 0 var(--aqua-line)}
:root[data-interface="aqua"] :is(#providers,#models,#settings){background:var(--aqua-glass);border:1px solid var(--aqua-line);border-radius:24px;box-shadow:var(--shadow)}
:root[data-interface="aqua"] .heading h1{font-weight:700;letter-spacing:-.035em}
:root[data-interface="aqua"] :is(.identity-avatar,.empty-mark){border-radius:14px;background:var(--accent)!important;color:var(--primary)!important}
:root[data-interface="aqua"] :is(#provider-list,#my-models){gap:12px;border:0;overflow:visible;background:transparent}
:root[data-interface="aqua"] #provider-list>.identity-item,:root[data-interface="aqua"] #my-models>.model-row{background:var(--aqua-glass);border:1px solid var(--aqua-line);border-radius:20px;box-shadow:var(--shadow)}
:root[data-interface="aqua"] #my-models .identity-item{border-radius:20px;background:transparent}
:root[data-interface="aqua"] #provider-list>.identity-item:hover,:root[data-interface="aqua"] #my-models .identity-item:hover{background:var(--surface)}
:root[data-interface="aqua"] .identity-body>strong{font-size:1rem}
:root[data-interface="aqua"] .identity-body>small{font-size:.82rem}
:root[data-interface="aqua"] :is(.composer,.conversation-menu,.attachment-popover,.sidebar-filter-menu,.key-more-actions){background:var(--card);border-radius:18px;box-shadow:var(--shadow)}
:root[data-interface="aqua"] .composer{border-color:var(--border);padding:12px}
:root[data-interface="aqua"] .composer:focus-within{border-color:var(--primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--primary) 16%,transparent)}
:root[data-interface="aqua"] :is(.message.user,.message.assistant){border-radius:18px}
:root[data-interface="aqua"] .message.user{background:var(--accent);color:var(--ink)}
:root[data-interface="aqua"] .native-welcome{background:var(--card);border:1px solid var(--aqua-line);border-radius:24px;padding:28px;box-shadow:var(--shadow)}
:root[data-interface="aqua"] .native-welcome :is(p,small){font-size:.85rem}
:root[data-interface="aqua"] .native-welcome .welcome-shortcuts{font-size:.75rem}
:root[data-interface="aqua"] dialog{background:var(--card);border:1px solid var(--aqua-line);box-shadow:0 24px 80px #102b5140;border-radius:22px}
:root[data-interface="aqua"] dialog::backdrop{background:#132c4a66;backdrop-filter:blur(5px)}
:root[data-interface="aqua"] #settings .settings-workspace>.workspace-tabs{background:var(--aqua-glass);border-radius:18px}
@media(min-width:960px){
 :root[data-interface="aqua"] .shell{padding:0 24px 24px 264px}
 :root[data-interface="aqua"] .shell::before{display:none}
 :root[data-interface="aqua"] header{position:fixed;left:16px;top:16px;width:calc(100% - 32px);height:68px;min-height:68px;padding:8px 16px;border-radius:22px;justify-content:space-between}
 :root[data-interface="aqua"] .brand{position:static;left:auto;top:auto;flex:none;width:auto;gap:10px}
 :root[data-interface="aqua"] .brand .mark{width:42px;height:42px;font-size:24px}
 :root[data-interface="aqua"] .brand strong{font-size:1.1rem}
 :root[data-interface="aqua"] .header-actions #sidebar-toggle{position:static;left:auto;top:auto}
 :root[data-interface="aqua"] .bottom-nav{top:100px;left:16px;width:224px;padding:8px;gap:4px;border-radius:22px}
 :root[data-interface="aqua"] .bottom-nav button{border-radius:13px}
 :root[data-interface="aqua"] .desktop-sidebar{top:294px;bottom:16px;left:16px;width:224px;padding:12px;border-radius:22px}
 :root[data-interface="aqua"] .sidebar-footer{padding-bottom:4px}
 :root[data-interface="aqua"] .pane{padding-top:112px}
 :root[data-interface="aqua"] :is(#providers,#models,#settings){margin-top:100px;padding:24px}
 :root[data-interface="aqua"] :is(#providers,#models,#settings)>.heading{margin-top:0}
 :root[data-interface="aqua"] #playground{padding-top:100px}
 :root[data-interface="aqua"] #playground .chat-card{height:calc(100dvh - 124px);padding:12px 16px;border-radius:24px;background:var(--aqua-glass);border:1px solid var(--aqua-line);box-shadow:var(--shadow)}
 :root[data-interface="aqua"] :is(#provider-list,#my-models){grid-template-columns:repeat(2,minmax(0,1fr))}
 :root[data-interface="aqua"][data-sidebar-hidden="true"] .shell{padding-left:24px}
 :root[data-interface="aqua"][data-sidebar-hidden="true"] .brand{display:flex}
}
@media(max-width:959px){
 :root[data-interface="aqua"] .shell,:root[data-interface="aqua"] .shell:has(#playground:not([hidden])){padding-bottom:calc(124px + env(safe-area-inset-bottom))}
 :root[data-interface="aqua"] header{margin-top:12px;padding:10px 12px;gap:8px;border-radius:22px}
 :root[data-interface="aqua"] .brand{min-width:0;flex:1 1 7rem;gap:8px}
 :root[data-interface="aqua"] .brand strong{font-size:1rem;white-space:normal;overflow-wrap:anywhere}
 :root[data-interface="aqua"] .brand small{font-size:.625rem;letter-spacing:.08em}
 :root[data-interface="aqua"] .mark{width:32px;height:36px;font-size:22px}
 :root[data-interface="aqua"] .header-actions{margin-left:auto}
 :root[data-interface="aqua"] .heading{margin-top:24px}
 :root[data-interface="aqua"] :is(#providers,#models,#settings){margin-top:20px;padding:16px}
 :root[data-interface="aqua"] :is(#providers,#models,#settings)>.heading{margin-top:0}
 :root[data-interface="aqua"] .card{padding:16px}
 :root[data-interface="aqua"] .chat-card{padding:0;background:transparent;border:0;box-shadow:none}
 :root[data-interface="aqua"] .native-welcome{padding:20px;max-width:100%}
 :root[data-interface="aqua"] .bottom-nav{left:max(12px,env(safe-area-inset-left));right:max(12px,env(safe-area-inset-right));bottom:max(12px,env(safe-area-inset-bottom));width:auto;transform:none;padding:8px;gap:4px;border-radius:24px}
 :root[data-interface="aqua"] .bottom-nav button{border-radius:16px}
 :root[data-interface="aqua"] #settings .settings-workspace>.workspace-tabs{padding:6px}
}
@media(prefers-reduced-transparency:reduce){:root[data-interface="aqua"]{--card:var(--bg);--aqua-glass:var(--bg)}:root[data-interface="aqua"] body::before{display:none}}
`
