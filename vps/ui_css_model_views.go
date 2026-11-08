package main

const modelViewsCSS = `
.model-view-tools{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:12px;min-width:0}.model-view-tools .saved-view-label{flex:1 1 14rem;min-width:0;margin:0}.model-view-tools select{width:100%;min-width:0}.model-view-tools>button{font-size:.8rem}.model-view-form{padding:16px;margin-block:12px;background:var(--card);border:1px solid var(--border);border-radius:12px;min-width:0}.model-view-form p{margin:0}.model-view-form .actions{margin:0}.model-view-status{margin:12px 0;overflow-wrap:anywhere}.model-active-filters{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0;min-width:0}.filter-chip{display:inline-flex;align-items:center;gap:12px;max-width:100%;min-height:48px;padding:8px 12px;background:var(--surface);border:1px solid var(--border);color:var(--ink);font-size:.78rem}.filter-chip>span:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.filter-chip-remove{font-size:1.1rem;flex:none;color:var(--muted)}
@media(max-width:959px){html{scroll-padding-bottom:calc(96px + env(safe-area-inset-bottom))}.model-view-form button{scroll-margin-block:16px calc(96px + env(safe-area-inset-bottom))}.model-filter-strip{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:start}.model-filter-strip>.quick-filters{flex-wrap:nowrap;overflow-x:auto;overscroll-behavior-x:contain;scrollbar-width:thin;max-width:100%;padding-bottom:4px}.model-filter-strip>.quick-filters>button{flex:none;white-space:nowrap}#model-filters[open]{grid-column:1/-1;grid-row:2}.model-view-tools{gap:8px;margin-top:12px}.model-view-tools .saved-view-label{flex-basis:100%}.model-view-tools>button{flex:1 1 8rem;margin:0}.model-view-form{padding:16px}.model-view-form .actions>button{flex:1 1 8rem;margin:0}.model-active-filters{gap:8px}}

.model-collection-search{flex-wrap:wrap}
.model-collection-search>label{flex:1 1 12rem}
.model-scan-controls{display:flex;flex:0 1 19rem;gap:8px;min-width:0}
.model-scan-controls>label{flex:1;min-width:0;margin:0}
.model-scan-controls select{width:100%;min-width:0}
.model-scan-note{color:var(--muted);overflow-wrap:anywhere}
#models #my-models.has-model-performance{grid-template-columns:minmax(0,1fr)}
#my-models .model-performance-row{
 display:grid;grid-template-columns:auto minmax(0,1fr) auto;
 grid-template-areas:"icon identity arrow" "metrics metrics metrics";
 align-items:center;gap:10px 12px;
}
.model-performance-row>.identity-avatar{grid-area:icon}
.model-performance-row>.identity-body{grid-area:identity}
.model-performance-row>.item-arrow{grid-area:arrow}
.model-performance-values{
 grid-area:metrics;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));
 gap:8px;min-width:0;font-variant-numeric:tabular-nums;
}
.model-performance-cell{display:flex;flex-direction:column;align-items:flex-start;gap:3px;min-width:0;overflow-wrap:anywhere}
.model-performance-cell>span,.model-performance-cell>small{font-size:.72rem;color:var(--muted);margin:0}
.model-performance-cell>b{font-size:.96rem;line-height:1.25;color:var(--ink)}
@media(min-width:960px){
 #my-models .model-performance-row{
  grid-template-columns:auto minmax(0,1fr) minmax(0,43%) auto;
  grid-template-areas:"icon identity metrics arrow";
 }
}
@media(max-width:700px){
 #models .model-overview-heading{margin-bottom:12px}
 .model-overview-heading>p,#my-models .model-performance-row .model-id{display:none}
 .model-filter-strip>.quick-filters{flex-wrap:wrap;overflow:visible;padding-bottom:0}
 .model-filter-strip :is([data-model-quick="recent"],[data-model-quick="watchlist"]){display:none}
 #my-models .model-performance-row{padding:12px;row-gap:8px}
 .model-collection-search>label{flex:1 1 0}
 .model-collection-search>button{order:1}
 .model-scan-controls{order:2;flex-basis:100%}
}
`
