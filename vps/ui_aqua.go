package main

import (
	"bytes"
	"crypto/sha256"
	_ "embed"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// The artwork is bundled with the binary; switching appearance makes no
// third-party requests. Hash only when the optional interface requests it.
//
//go:embed assets/aqua-garden-v1.webp
var aquaGardenData []byte

var aquaGarden struct {
	sync.Once
	etag string
}

var aquaStyles = staticAsset{raw: aquaCSS}

func (s *Server) aquaStylesheet(w http.ResponseWriter, r *http.Request) {
	aquaStyles.serve(w, r, "text/css; charset=utf-8")
}

func (s *Server) aquaWallpaper(w http.ResponseWriter, r *http.Request) {
	aquaGarden.Do(func() {
		aquaGarden.etag = fmt.Sprintf("\"%x\"", sha256.Sum256(aquaGardenData))
	})
	w.Header().Set("Content-Type", "image/webp")
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("ETag", aquaGarden.etag)
	http.ServeContent(w, r, "aqua-garden-v1.webp", time.Time{}, bytes.NewReader(aquaGardenData))
}

const jsAqua = `
function applyInterface(value){
 if(value!=="aqua")value="classic";
 let stylesheet=$("aqua-styles");
 if(value==="aqua"&&!stylesheet){
  stylesheet=document.createElement("link");stylesheet.id="aqua-styles";stylesheet.rel="stylesheet";stylesheet.href="/aqua.css";
  stylesheet.addEventListener("error",()=>{stylesheet.remove();if(document.documentElement.dataset.interface==="aqua"){delete document.documentElement.dataset.interface;$("appearance-interface").value="classic";notice("Aqua 樣式暫時無法載入，已使用經典介面。可重新選取 Aqua 再試一次。",true);}});
  document.head.append(stylesheet);
 }
 if(stylesheet)stylesheet.disabled=value!=="aqua";
 if(value==="aqua")document.documentElement.dataset.interface="aqua";else delete document.documentElement.dataset.interface;
 $("appearance-interface").value=value;
}
function bindAquaInterface(){
 let value="classic";try{value=localStorage.getItem("modelatlas.interface")||value;}catch{}
 applyInterface(value);
 $("appearance-interface").addEventListener("change",()=>{const value=$("appearance-interface").value;applyInterface(value);try{localStorage.setItem("modelatlas.interface",value);}catch{}});
}
`
