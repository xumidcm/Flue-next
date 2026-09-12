import {mkdir,writeFile} from 'node:fs/promises';
import {TOOLS} from './public/agent.js';
import {BASE_PROMPT,GUIDES,SOURCES} from './public/knowledge.js';
const base=new URL('./contracts/',import.meta.url);
await mkdir(base,{recursive:true});
await writeFile(new URL('tools.json',base),JSON.stringify(TOOLS,null,2)+'\n');
await writeFile(new URL('system-prompt.txt',base),BASE_PROMPT+'\n');
await writeFile(new URL('knowledge.json',base),JSON.stringify({guides:GUIDES,sources:SOURCES},null,2)+'\n');
console.log('Exported portable tool schemas, system prompt and format knowledge.');
