package main

const conversationCreationCSS = `
.conversation-create-state{display:flex;align-items:center;gap:16px;padding:14px 16px;margin:0 auto 18px;max-width:1040px;border:1px solid var(--border);border-radius:12px;background:var(--card);min-width:0}
.conversation-create-state>span{display:grid;place-items:center;width:20px;height:20px;flex:none;color:var(--muted)}
.conversation-create-state>span>.icon{width:20px;height:20px}
.conversation-create-state>div{flex:1;min-width:0}
.conversation-create-state strong{display:block;font-size:.85rem;line-height:1.5}
.conversation-create-state p{margin:4px 0;font-size:.8rem;line-height:1.7;color:var(--muted);overflow-wrap:anywhere}
.conversation-create-state small{font-size:.72rem;color:var(--muted);overflow-wrap:anywhere}
.conversation-create-state>button{min-height:48px;flex:none;font-size:.78rem;gap:6px}
#work-chat:has(#conversation-create-state:not([hidden])){display:flex;flex-direction:column;height:calc(100dvh - 164px);min-height:34rem}
#work-chat:has(#conversation-create-state:not([hidden]))>.conversation-create-state{flex:none;width:100%}
#work-chat:has(#conversation-create-state:not([hidden]))>.chat-card:has(#chat-ui:not([hidden])){width:100%;height:auto;min-height:0;flex:1}
#work-chat:has(#conversation-create-state:not([hidden])) .chat-viewport{min-height:4rem}
@media(max-width:959px){
 #work-chat:has(#conversation-create-state:not([hidden])){height:calc(100dvh - 190px - env(safe-area-inset-top) - env(safe-area-inset-bottom))}
 .conversation-create-state{gap:12px;flex-wrap:wrap;padding:14px}
 .conversation-create-state>div{flex-basis:calc(100% - 36px)}
 .conversation-create-state>button{margin-left:32px;max-width:calc(100% - 32px);white-space:normal}
}
@media(prefers-reduced-motion:no-preference){:root:not([data-reduce-motion="true"]) #new-session[aria-busy="true"]>.icon{animation:workspace-read-spin 1.4s linear infinite}}
`
