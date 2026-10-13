package main

const appJS = jsCore + jsViews + jsChat
const jsCore = `
"use strict";
const $ = id => document.getElementById(id);
let providers=[],models=[],myModels=[],sessions=[];
function notice(msg,bad=false){$("notice").textContent=msg;$("notice").style.color=bad?"#c24740":"";}
async function api(path,opts={}){const r=await fetch("/api/v1"+path,{credentials:"same-origin",cache:"no-store",...opts});if(!r.ok)throw Error("HTTP "+r.status);return r.json();}
async function submit(path,data,method="POST"){return api(path,{method,headers:{"Content-Type":"application/json"},body:JSON.stringify(data)});}
function data(form){return Object.fromEntries(new FormData(form).entries());}
function option(select,value,title){const n=document.createElement("option");n.value=value;n.textContent=title;select.append(n);}
function item(target,title,detail){const n=document.createElement("div");n.className="item";const a=document.createElement("strong");a.textContent=title;const b=document.createElement("small");b.textContent=detail;n.append(a,b);target.append(n);}
function show(tab){document.querySelectorAll(".pane").forEach(n=>n.hidden=n.id!==tab);document.querySelectorAll(".bottom-nav button").forEach(b=>{b.classList.toggle("active",b.dataset.tab===tab);if(b.dataset.tab===tab)b.setAttribute("aria-current","page");else b.removeAttribute("aria-current");});if(tab==="settings")loadStatus();}
function bubble(role,text){const n=document.createElement("div");n.className="bubble "+(role==="user"?"user":"assistant");n.textContent=text;$("chat").append(n);$("chat").scrollTop=$("chat").scrollHeight;return n;}
`
