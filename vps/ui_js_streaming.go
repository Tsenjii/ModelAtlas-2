package main

// Native DOM updates keep streaming independent of a frontend framework.
const jsStreaming = `
function streamText(target,changed){
 const text=document.createTextNode("");target.replaceChildren(text);
 let parts=[],size=0,frame=null,timer=null,closed=false;
 const flush=()=>{
  if(frame!==null)cancelAnimationFrame(frame);if(timer!==null)clearTimeout(timer);frame=timer=null;
  if(!parts.length)return;const value=parts.join("");parts=[];size=0;text.appendData(value);changed();
 };
 return {
  append(value){
   if(closed||!value)return;parts.push(value);size+=value.length;
   if(size>=32768){flush();return;}
   if(frame===null){frame=requestAnimationFrame(flush);timer=setTimeout(flush,80);}
  },
  finish(){flush();closed=true;}
 };
}

let chatFollowing=true,chatUnread=false,chatScrollFrame=null,chatLastTop=0;
function chatAtBottom(){const chat=$("chat");return chat.scrollHeight-chat.clientHeight-chat.scrollTop<=4;}
function chatFollowButton(){
 const button=$("chat-latest");button.hidden=chatFollowing||chatAtBottom();
 button.setAttribute("aria-label",chatUnread?"有新回應，回到最新訊息":"回到最新訊息");
 button.title=chatUnread?"有新回應":"回到最新訊息";$("chat-unread").hidden=!chatUnread;
}
function pauseChatFollow(){
 chatFollowing=false;if(chatScrollFrame!==null)cancelAnimationFrame(chatScrollFrame);chatScrollFrame=null;chatFollowButton();
}
function chatChanged(force=false,unread=true){
 if(force){chatFollowing=true;chatUnread=false;chatFollowButton();}
 if(!chatFollowing){if(unread)chatUnread=true;chatFollowButton();return;}
 if(chatScrollFrame!==null)return;
 chatScrollFrame=requestAnimationFrame(()=>{
  chatScrollFrame=null;if(!chatFollowing)return;const chat=$("chat");chat.scrollTop=chat.scrollHeight;
  chatLastTop=chat.scrollTop;chatUnread=false;chatFollowButton();
 });
}
function chatHistoryReady(){chatChanged(true,false);}
function chatReadPosition(){
 if(chatFollowing)return null;const chat=$("chat"),top=chat.getBoundingClientRect().top;
 const anchor=[...chat.querySelectorAll(".message[data-message-id]")].find(node=>node.getBoundingClientRect().bottom>top);
 return {top:chat.scrollTop,id:anchor?.dataset.messageId,offset:anchor?anchor.getBoundingClientRect().top-top:0};
}
function restoreChatReadPosition(position){
 pauseChatFollow();const chat=$("chat"),anchor=[...chat.querySelectorAll(".message[data-message-id]")].find(node=>node.dataset.messageId===position.id);
 chat.scrollTop=anchor?chat.scrollTop+anchor.getBoundingClientRect().top-chat.getBoundingClientRect().top-position.offset:position.top;
 chatLastTop=chat.scrollTop;chatFollowButton();
}
function bindChatExperience(){
 const chat=$("chat");
 chat.addEventListener("scroll",()=>{
  if(chatAtBottom()){chatFollowing=true;chatUnread=false;}
  else if(chat.scrollTop<chatLastTop-1)pauseChatFollow();
  chatLastTop=chat.scrollTop;chatFollowButton();
 },{passive:true});
 chat.addEventListener("wheel",event=>{if(event.deltaY<0&&chat.scrollHeight>chat.clientHeight)pauseChatFollow();},{passive:true});
 let touchY=null;
 chat.addEventListener("touchstart",event=>{touchY=event.touches[0]?.clientY??null;},{passive:true});
 chat.addEventListener("touchmove",event=>{if(touchY!==null&&event.touches[0]?.clientY>touchY+2)pauseChatFollow();},{passive:true});
 chat.addEventListener("keydown",event=>{if(["ArrowUp","PageUp","Home"].includes(event.key))pauseChatFollow();});
 chat.addEventListener("click",event=>{const summary=event.target.closest("summary");if(summary&&!summary.parentElement.open)pauseChatFollow();});
 chat.addEventListener("toggle",()=>chatChanged(false,false),true);
 $("chat-latest").addEventListener("click",()=>{chat.focus({preventScroll:true});chatChanged(true,false);});
 if("ResizeObserver" in window)new ResizeObserver(()=>chatChanged(false,false)).observe(chat);
 document.addEventListener("visibilitychange",()=>{if(!document.hidden)chatChanged(false,false);});
 document.addEventListener("keydown",event=>{
  if(event.isComposing||event.keyCode===229||event.repeat||document.querySelector("dialog[open]"))return;
  const command=(event.ctrlKey||event.metaKey)&&!event.altKey,key=event.key.toLowerCase();
  if(key==="escape"&&$("conversation-more").open){event.preventDefault();$("conversation-more").open=false;$("conversation-more").querySelector("summary").focus({preventScroll:true});return;}
  const run=fn=>Promise.resolve().then(fn).catch(error=>notice(error.message,true));
  if(command&&key==="k"){event.preventDefault();if(busy){notice("請先等待目前請求完成");return;}show("playground");workspace("playground","work-chat");if(event.shiftKey)run(conversationLibrary);else openModelPicker();return;}
  if(command&&key==="o"&&event.shiftKey){event.preventDefault();if(busy){notice("請先等待目前請求完成");return;}show("playground");workspace("playground","work-chat");run(newConversation);return;}
  if($("playground").hidden||$("chat-ui").hidden)return;
  if(command&&key==="enter"&&!event.shiftKey&&event.target===$("prompt")){event.preventDefault();if(!busy)run(sendChat);return;}
  if(key==="escape"&&busy&&!$("stop").hidden){event.preventDefault();run(stopRequest);}
 });
}
`
