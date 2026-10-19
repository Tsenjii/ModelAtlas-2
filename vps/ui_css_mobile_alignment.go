package main

// One mobile spacing system for pages, collections, forms and every dialog.
// Keep desktop layout and the existing source/scope owners unchanged.
const mobileAlignmentCSS = `
@media(max-width:959px){
 :root{--mobile-gutter:16px;--mobile-inset:16px;--mobile-radius:12px}
 .shell{padding-inline:max(var(--mobile-gutter),env(safe-area-inset-left)) max(var(--mobile-gutter),env(safe-area-inset-right));padding-bottom:calc(100px + env(safe-area-inset-bottom))}
 header{min-height:72px;padding-block:12px;gap:8px}.brand{gap:8px}.header-actions{gap:4px}.header-actions #header-assistant,.header-actions #settings-toggle{width:48px;height:48px;padding:0;font-size:0}.header-actions .icon{width:20px;height:20px}
 .shell:has(#playground:not([hidden])){padding-bottom:calc(100px + env(safe-area-inset-bottom))}
 .pane,.workspace-panel,.native-model-collection,.settings-workspace{min-width:0;max-width:100%}
 .heading{margin:24px 0 16px}.heading-actions{gap:12px;align-items:center}.heading-actions>div{flex:1 1 9rem}.heading h1{margin-bottom:6px}.heading p{font-size:.85rem}
 .workspace-tabs,[data-workspace-tabs="models"]{display:flex;flex-wrap:nowrap;gap:4px;overflow-x:auto;overscroll-behavior-x:contain;scrollbar-width:thin;scroll-padding-inline:4px;padding:0 0 8px;margin:0 0 16px;max-width:100%}
 .workspace-tabs>button{flex:0 0 auto;min-width:48px;min-height:48px;padding:10px 12px;white-space:nowrap;font-size:.82rem}
 .source-tabs{padding:4px;margin-bottom:16px}.source-tabs>button{flex:1 0 auto}
 .card,details.card[open]{padding:var(--mobile-inset);border-radius:var(--mobile-radius);margin-bottom:16px}.columns{grid-template-columns:1fr;gap:16px}.provider-grid,#my-models,#catalog{grid-template-columns:1fr}
 .section-heading{padding-bottom:16px;margin-bottom:16px}.section-heading h2{margin-bottom:6px}.section-heading p{margin-bottom:0}
 .collection-tools,.catalog-searchbar,.catalog-filter-grid{grid-template-columns:minmax(0,1fr);gap:12px}.collection-search{gap:12px;min-width:0}.collection-search>label{min-width:0}.collection-meta{margin:8px 0;min-height:48px;flex-wrap:wrap}
 .quick-filters{gap:4px}.quick-filters button{padding:8px 12px;font-size:.78rem}.model-filter-strip{gap:8px;margin-top:12px}#model-filters[open]{padding:12px 16px 16px}
 #provider-list>.identity-item,#my-models .identity-item,.identity-item{padding:16px;gap:12px}.identity-avatar{width:36px;height:36px}.identity-body>strong{font-size:.94rem}.identity-body>small{font-size:.78rem}.item-arrow{flex-shrink:0}
 .checkin-summary,.selection-bar,.connection-speed-card,.provider-account-card,.plot-card,.comparison-results>.detail-section{padding:16px;border-radius:var(--mobile-radius)}
 .selection-bar>.actions,.detail-primary-actions,.connection-speed-actions{gap:8px}.selection-bar .actions>button,.detail-primary-actions>button,.connection-speed-actions>button{flex:1 1 8rem;margin-right:0}
 .toolbar{gap:12px;min-width:0}.toolbar>label{min-width:min(100%,12rem);flex:1 1 12rem}.toolbar>button{flex:1 1 10rem}.toolbar.composer-tools>button{flex:none}
 form{gap:16px;min-width:0;grid-template-columns:minmax(0,1fr)}input,select,textarea{font-size:max(1rem,16px)}label{font-size:.85rem}.card>label+label{margin-top:16px}
 #settings-form>button{justify-self:stretch;width:100%}#settings-general,#settings-services,#settings-data{max-width:none;margin-inline:0}
 #assistant-config-form,#assistant-form,#research-workspace>form{padding:16px;border-radius:var(--mobile-radius)}#assistant-panel>.toolbar{margin-bottom:16px}
 #settings-requests>.toolbar{margin-bottom:16px}.observation-tools{grid-template-columns:repeat(auto-fit,minmax(min(100%,10rem),1fr));gap:12px}.observation-tools button{grid-column:1/-1}.observation-row summary{min-height:48px}
 .metrics{grid-template-columns:repeat(auto-fit,minmax(min(100%,8rem),1fr));gap:12px}.metrics>.item{padding:12px;min-width:0}.site-metrics{gap:16px}.detail-rows>div{gap:6px 16px}
 .provider-account-card .item{column-gap:12px}.provider-account-heading{flex-wrap:wrap;gap:8px}.account-count{margin-left:0}.key-more-actions{width:min(220px,calc(100vw - 100px))}
 .connection-controls{flex-wrap:wrap;gap:12px}.connection-controls label{max-width:none;flex:1 1 12rem}.connection-controls>button{flex:1 1 10rem}.connection-speed-heading{flex-wrap:wrap;gap:8px}.connection-speed-heading .state-chip{max-width:100%;margin-top:0}
 .chat-card{padding:0;border-radius:0;background:transparent;border:0}.conversation-bar{gap:8px;padding:8px 0 12px}.conversation-controls{gap:0}.chat{padding-inline:0}.native-welcome{padding:24px 0}.composer{width:100%;margin-inline:0;padding:8px}.composer textarea{font-size:max(1rem,16px)!important;min-height:48px!important}.composer-tools{flex-wrap:nowrap;gap:4px!important}.composer-tools .model-picker-trigger{flex:1 1 0;max-width:calc(100% - 104px);padding-inline:4px}.composer-tools .send-actions{flex:0 0 auto}.conversation-menu{width:min(440px,calc(100vw - 32px));padding:16px}.conversation-menu-heading{top:-16px}
 .bottom-nav{gap:4px;padding:8px max(16px,env(safe-area-inset-right)) calc(8px + env(safe-area-inset-bottom)) max(16px,env(safe-area-inset-left))}.bottom-nav button{flex:1 1 0;flex-direction:column;gap:3px;min-height:58px;padding:6px 2px;font-size:min(.75rem,4.6vw)}.bottom-nav .icon{width:20px;height:20px}
 /* All five native dialogs use the same frame, header and content inset. */
 dialog,#detail[data-kind],#model-picker,#provider-editor,#request-receipt{inset:max(16px,env(safe-area-inset-top)) max(16px,env(safe-area-inset-right)) max(16px,env(safe-area-inset-bottom)) max(16px,env(safe-area-inset-left));width:calc(100% - max(16px,env(safe-area-inset-left)) - max(16px,env(safe-area-inset-right)));max-width:720px;max-height:calc(100dvh - max(16px,env(safe-area-inset-top)) - max(16px,env(safe-area-inset-bottom)));padding:0;border-radius:var(--mobile-radius);overflow:hidden}
 dialog[open]{display:flex;flex-direction:column}
 dialog>.dialog-bar,#detail[data-kind]>.dialog-bar,#provider-editor>.dialog-bar{position:static;flex:0 0 auto;top:0;padding:12px 16px;margin:0;gap:12px;border-bottom:1px solid var(--border)}
 .dialog-bar h2,.model-picker-head h2{font-size:1.05rem;line-height:1.4;margin:0;min-width:0}.dialog-bar>button,.model-picker-head>button{flex:none;min-width:48px;min-height:48px;padding-inline:12px}
 #detail-content,#provider-editor-body,#request-receipt-content,#review-form{min-width:0;min-height:0;flex:1 1 auto;overflow:auto;overscroll-behavior:contain;padding:16px;scroll-padding-block:16px}
 dialog form{margin:16px 0}dialog form:first-child,#review-form,#provider-editor form{margin-top:0}#review-form{margin:0;gap:16px}
 #review-form>button,#provider-editor form>button:not(.clear-draft){position:sticky;bottom:0;z-index:2;min-height:48px;box-shadow:0 0 0 12px var(--card)}
 #detail:is([data-kind="provider"],[data-kind="model"]){height:calc(100dvh - max(16px,env(safe-area-inset-top)) - max(16px,env(safe-area-inset-bottom)))}
 #detail:is([data-kind="provider"],[data-kind="model"]) #detail-content{padding:16px 16px 0;overflow:hidden}
 #detail:is([data-kind="provider"],[data-kind="model"]) .detail-hero{margin:0 0 16px;gap:12px}
 #detail:is([data-kind="provider"],[data-kind="model"]) .detail-tabs{margin:0;padding-bottom:8px}
 #detail:is([data-kind="provider"],[data-kind="model"]) .detail-panel{padding:16px 0;scrollbar-gutter:auto;scroll-padding-block:16px}
 .detail-section{padding:0 0 16px;margin-bottom:16px}.detail-section>h3{margin:0 0 12px}.detail-primary-actions{margin:0 0 16px}
 .model-picker-head{display:grid;grid-template-columns:minmax(0,1fr) auto;position:sticky;top:0;z-index:3;background:var(--card);flex:0 0 auto;gap:8px 12px;padding:12px 16px}.model-picker-head>div{display:contents}.model-picker-head h2{grid-column:1;grid-row:1;align-self:center}.model-picker-head>button{grid-column:2;grid-row:1}.model-picker-head p{grid-column:1/-1;grid-row:2;margin:0}.model-picker-filters{flex:0 0 auto;padding:4px 16px 16px;grid-template-columns:minmax(0,1fr);gap:12px}.model-picker-filters>.check{white-space:normal}.model-picker-search input:focus-visible{outline:0}
 #model-picker{overflow:auto;scroll-padding-block:16px}
 #model-picker-results{flex:1 1 auto;min-height:48px;max-height:42dvh}.model-choice{padding:16px;gap:12px}.model-picker-more{margin:12px 16px}.model-picker-footer{flex:none;padding:12px 16px;gap:8px}.model-picker-footer>span{flex-basis:100%}.model-picker-footer>button{flex:1 1 8rem;scroll-margin-block:16px}
}
/* A short viewport (including an open software keyboard) needs one scroller. */
@media(max-width:959px) and (max-height:560px){
 dialog,#detail[data-kind],#model-picker,#provider-editor{overflow:auto}
 dialog>.dialog-bar,#detail[data-kind]>.dialog-bar,#provider-editor>.dialog-bar{position:sticky;top:0;z-index:4}
 #detail:is([data-kind="provider"],[data-kind="model"]) #detail-content{display:block;overflow:visible;flex:none}
 #detail:is([data-kind="provider"],[data-kind="model"]) .detail-panel{overflow:visible;flex:none}
 #provider-editor-body,#request-receipt-content,#review-form,#detail:not([data-kind]) #detail-content{overflow:visible;flex:none}
 #model-picker-results{overflow:visible;max-height:none;flex:none}.model-picker-head,.model-picker-filters,.model-picker-footer{flex:none}
}
`
