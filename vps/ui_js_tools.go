package main

const jsTools = `
function renderToolResults(){
 const box=$("tool-results");box.replaceChildren();$("prompt").required=!pendingTools.length;if(!pendingTools.length)return;
 box.append(el("h3","模型需要工具結果"));const form=el("form");box.append(form);
 pendingTools.forEach(call=>{const card=el("div",undefined,"card");card.append(el("strong",call.function.name),el("pre",call.function.arguments));const input=field(card,"工具結果","textarea");input.name=call.id;input.required=true;input.maxLength=32000;form.append(card);});
 const button=el("button","送回結果並繼續");button.disabled=busy;form.append(button);form.addEventListener("submit",e=>{e.preventDefault();const results=pendingTools.map(c=>({call_id:c.id,content:form.elements.namedItem(c.id).value}));sendChat(results);});
}
`
