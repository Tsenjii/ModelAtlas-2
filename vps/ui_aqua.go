package main

import (
	"bytes"
	"crypto/sha256"
	_ "embed"
	"fmt"
	"net/http"
	"path"
	"sync"
	"time"
)

// User illustrations retain their original JPEG bytes; chibi WebP retains exact RGBA pixels.
//
//go:embed assets/aqua-kotoha-v1.jpg
var aquaKotoha []byte

//go:embed assets/aqua-elaina-v1.jpg
var aquaElaina []byte

//go:embed assets/aqua-kotoha-chibi-v1.webp
var aquaKotohaChibi []byte

//go:embed assets/aqua-elaina-chibi-v1.webp
var aquaElainaChibi []byte

type aquaArtwork struct {
	data []byte
	mime string
	once sync.Once
	etag string
}

var aquaArtworks = map[string]*aquaArtwork{
	"/assets/aqua-kotoha-v1.jpg":        {data: aquaKotoha, mime: "image/jpeg"},
	"/assets/aqua-elaina-v1.jpg":        {data: aquaElaina, mime: "image/jpeg"},
	"/assets/aqua-kotoha-chibi-v1.webp": {data: aquaKotohaChibi, mime: "image/webp"},
	"/assets/aqua-elaina-chibi-v1.webp": {data: aquaElainaChibi, mime: "image/webp"},
}
var aquaStyles = staticAsset{raw: aquaCSS}

func (s *Server) aquaStylesheet(w http.ResponseWriter, r *http.Request) {
	aquaStyles.serve(w, r, "text/css; charset=utf-8")
}
func (s *Server) aquaWallpaper(w http.ResponseWriter, r *http.Request) {
	art := aquaArtworks[r.URL.Path]
	if art == nil {
		http.NotFound(w, r)
		return
	}
	art.once.Do(func() { art.etag = fmt.Sprintf("\"%x\"", sha256.Sum256(art.data)) })
	w.Header().Set("Content-Type", art.mime)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("ETag", art.etag)
	http.ServeContent(w, r, path.Base(r.URL.Path), time.Time{}, bytes.NewReader(art.data))
}

const jsAqua = `
const aquaWallpapers={kotoha:{url:'/assets/aqua-kotoha-v1.jpg',label:'Kotoha · 原圖'},elaina:{url:'/assets/aqua-elaina-v1.jpg',label:'伊蕾娜 · 原圖'},'kotoha-chibi':{url:'/assets/aqua-kotoha-chibi-v1.webp',label:'Kotoha · Q版'},'elaina-chibi':{url:'/assets/aqua-elaina-chibi-v1.webp',label:'伊蕾娜 · Q版'}};
function interfaceAccentLabel(){const option=$('appearance-accent').querySelector('option[value="graphite"]');if(option)option.textContent=document.documentElement.dataset.interface==='aqua'?'水光藍':'石墨灰';}
function applyWallpaper(value){if(!Object.hasOwn(aquaWallpapers,value))value='kotoha';const art=aquaWallpapers[value],active=document.documentElement.dataset.interface==='aqua';document.documentElement.dataset.wallpaper=value;$('appearance-wallpaper').value=value;$('appearance-wallpaper-field').hidden=!active;const preview=$('appearance-wallpaper-preview');if(active){document.documentElement.style.setProperty('--aqua-wallpaper','url("'+art.url+'")');preview.src=art.url;preview.alt=art.label+' 背景預覽';}else preview.removeAttribute('src');}
function applyInterface(value){
 if(value!=='aqua')value='classic';let stylesheet=$('aqua-styles');
 if(value==='aqua'&&!stylesheet){stylesheet=document.createElement('link');stylesheet.id='aqua-styles';stylesheet.rel='stylesheet';stylesheet.href='/aqua.css';stylesheet.addEventListener('error',()=>{stylesheet.remove();if(document.documentElement.dataset.interface==='aqua'){delete document.documentElement.dataset.interface;$('appearance-interface').value='classic';interfaceAccentLabel();applyWallpaper($('appearance-wallpaper').value);notice('Aqua 樣式暫時無法載入，已使用經典介面。可重新選取 Aqua 再試一次。',true);}});document.head.append(stylesheet);}
 if(stylesheet)stylesheet.disabled=value!=='aqua';if(value==='aqua')document.documentElement.dataset.interface='aqua';else delete document.documentElement.dataset.interface;$('appearance-interface').value=value;interfaceAccentLabel();applyWallpaper($('appearance-wallpaper').value);
}
function bindAquaInterface(){let value='classic',wallpaper='kotoha';try{value=localStorage.getItem('modelatlas.interface')||value;wallpaper=localStorage.getItem('modelatlas.wallpaper')||wallpaper;}catch{}applyWallpaper(wallpaper);applyInterface(value);$('appearance-interface').addEventListener('change',()=>{const value=$('appearance-interface').value;applyInterface(value);try{localStorage.setItem('modelatlas.interface',value);}catch{}});$('appearance-wallpaper').addEventListener('change',()=>{const value=$('appearance-wallpaper').value;applyWallpaper(value);try{localStorage.setItem('modelatlas.wallpaper',value);}catch{}});}
`
