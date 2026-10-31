package main

const conversationWorkspaceCSS = `
#detail[data-kind="conversations"]{max-width:880px}
.conversation-library-tools{display:grid;grid-template-columns:minmax(0,1fr) minmax(120px,.3fr);gap:12px 16px;margin-bottom:12px;align-items:end}
.conversation-library-tools>.check{grid-column:1/-1;margin:0}.conversation-library-tools label{min-width:0}
.conversation-library-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:48px;margin-bottom:12px}.conversation-library-summary p{color:var(--muted);font-size:.85rem;margin:0}.conversation-library-summary button{flex:none}
.conversation-list{gap:12px;margin-bottom:16px}.conversation-list[aria-busy="true"]{cursor:progress}.conversation-card{padding:18px 20px;border-radius:14px;transition:border-color .15s ease,background .15s ease}
.conversation-card strong{display:flex;align-items:flex-start;gap:8px;line-height:1.5}.conversation-card strong .icon{width:17px;height:17px;margin-top:.2em;color:var(--primary)}.conversation-card>small{white-space:pre-line;font-size:.8rem;line-height:1.6;margin-top:8px}
.conversation-card-preview{color:var(--ink);font-size:.87rem;font-weight:400;line-height:1.65;margin:12px 0;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}
.conversation-card-meta{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;color:var(--muted);font-size:.75rem;font-weight:400}.conversation-library-empty{padding:24px 16px}.conversation-library-empty p{margin-bottom:0}
.conversation-title-form{margin-bottom:16px}.conversation-title-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.conversation-draft-hint{font-size:.8rem;color:var(--muted);margin:0}.conversation-management-actions{padding-bottom:20px;margin-bottom:20px;border-bottom:1px solid var(--border)}.conversation-change-status{overflow-wrap:anywhere}
@media(max-width:600px){.conversation-library-tools{grid-template-columns:minmax(0,1fr)}.conversation-library-tools>.check{grid-column:auto}.conversation-card{padding:16px}.conversation-library-summary{align-items:flex-start;flex-wrap:wrap}.conversation-title-actions>button{flex:1 1 auto}.conversation-management-actions>:is(button,a){flex:1 1 auto}}
@media(prefers-reduced-motion:reduce){.conversation-card{transition:none}}
`
