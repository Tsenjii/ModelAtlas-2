// Read every byte of the actual browser download using the supported streaming
// API, valid for both local Chromium and the suite's protocol connection.
// Keep original byte/JSON/format assertions at each caller.
async function readDownload(pending,encoding){
 const download=await pending,stream=await download.createReadStream();
 if(!stream)throw Error('The actual browser download has no readable stream');
 const chunks=[];for await(const chunk of stream)chunks.push(chunk);
 const bytes=Buffer.concat(chunks);return encoding?bytes.toString(encoding):bytes;
}
module.exports={readDownload};
