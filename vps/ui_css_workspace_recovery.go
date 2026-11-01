package main

const workspaceRecoveryCSS = `
:root[data-workspace-ready="false"] #playground .chat-card{display:none}
:root[data-workspace-ready="false"] #playground{min-height:1px}
.workspace-state{display:grid;grid-template-columns:40px minmax(0,1fr) auto;gap:16px;align-items:start;padding:22px 24px;margin:20px auto 24px;border:1px solid var(--border);border-radius:14px;background:var(--card);max-width:1150px;min-width:0}
.workspace-state-icon{display:grid;place-items:center;width:40px;height:40px;border-radius:10px;background:var(--surface);color:var(--muted)}
.workspace-state-icon>.icon{width:20px;height:20px}
.workspace-state-content{min-width:0}
.workspace-state-eyebrow{margin:0 0 5px!important;font-size:.7rem!important;letter-spacing:.035em;color:var(--muted)}
.workspace-state h2{margin:0 0 8px;font-size:1rem;line-height:1.4;letter-spacing:-.02em}
.workspace-state p{margin:0;font-size:.82rem;line-height:1.7;overflow-wrap:anywhere;color:var(--muted)}
.workspace-state small{display:block;color:var(--muted);font-size:.73rem;margin-top:12px}
.workspace-state .workspace-state-problem{margin-top:8px;color:var(--danger)}
.workspace-state>button{min-height:48px;align-self:center;font-size:.8rem;gap:7px;white-space:normal}
.workspace-state[data-state="error"] .workspace-state-icon,.workspace-state[data-state="offline"] .workspace-state-icon{color:var(--danger)}
.workspace-state-progress{display:grid;gap:7px;margin-top:18px;max-width:360px}
.workspace-state-progress span{height:7px;border-radius:4px;background:var(--surface)}
.workspace-state-progress span:nth-child(2){width:82%}
.workspace-state-progress span:nth-child(3){width:58%}
.workspace-read-status{display:block;font-size:.68rem;margin-top:5px}
.header-actions #header-refresh{position:relative}
.header-actions #header-refresh[data-state="error"]::after,.header-actions #header-refresh[data-state="offline"]::after{content:"";width:6px;height:6px;border-radius:50%;background:var(--danger);position:absolute;right:8px;bottom:8px}
@media(prefers-reduced-motion:no-preference){
 :root:not([data-reduce-motion="true"]) #header-refresh[aria-busy="true"]>.icon,
 :root:not([data-reduce-motion="true"]) .workspace-state[data-state="loading"]>.workspace-state-icon>.icon{animation:workspace-read-spin 1.4s linear infinite}
 @keyframes workspace-read-spin{to{transform:rotate(360deg)}}
}
@media(min-width:960px){
 .workspace-state{margin-top:82px}
 .shell:has(#workspace-state:not([hidden])) .pane{padding-top:24px}
 .shell:has(#workspace-state:not([hidden])) #playground .chat-card:has(#chat-ui:not([hidden])){height:auto;min-height:32rem}
 .shell:has(#workspace-state:not([hidden])) #playground .chat-viewport{min-height:20rem}
}
@media(max-width:959px){
 .header-actions #header-refresh{display:inline-flex;width:48px;height:48px;padding:0}
 .workspace-state{grid-template-columns:36px minmax(0,1fr);gap:12px;padding:16px;margin:16px 0 20px;border-radius:var(--mobile-radius,12px)}
 .workspace-state-icon{width:36px;height:36px}
 .workspace-state>button{grid-column:2;justify-self:start}
 .workspace-state h2{font-size:1rem}
 .workspace-state p{font-size:.85rem}
 .workspace-state-progress{margin-top:14px}
 .shell:has(#workspace-state:not([hidden])) .chat-card:has(#chat-ui:not([hidden])){height:auto;min-height:30rem}
}
@media(max-width:359px){.brand .mark{display:none}}
`
