const store = {};
const mk = id => ({ id, innerHTML:"", value:"", checked:false, dataset:{}, vars:{},
  style:{ setProperty(k,v){ store.__vars = store.__vars || {}; store.__vars[k]=v; } },
  addEventListener(){}, getBoundingClientRect:()=>({left:0,top:0,bottom:0,width:0,height:0}),
  setAttribute(){}, getAttribute(){return "true";}, removeAttribute(){},
  querySelectorAll(sel){ return (this.innerHTML.match(/class="b /g)||[]).map(()=>mk("bar")); } });
const el = id => store[id] || (store[id] = mk(id));
global.document = { getElementById: el, querySelectorAll: sel => {
  if (sel === "[data-s]") return ["A","B","C","D"].map(s => ({dataset:{s}, setAttribute(){}, getAttribute(){return "true";}, set onclick(f){}}));
  if (sel.startsWith(".zoom button")) return [3,6,11,22].map(z => ({dataset:{z}, setAttribute(){}, removeAttribute(){}, set onclick(f){}}));
  return []; } };
global.window = global; global.innerWidth=1400; global.innerHeight=900;
const js = require("fs").readFileSync(process.argv[2],"utf8").split("<script>")[1].split("</script>")[0];
const ctx = eval(js + "\n; ({draw, row, devs, byId, TASKS, setZ:(v)=>{Z=v}, setLW:(v)=>{LW=v}})");
const g = () => el("grid").innerHTML;
const count = s => (g().match(new RegExp(s,"g"))||[]).length;
console.log("head:", el("head").innerHTML.replace(/\s+/g," ").trim().slice(0,90));
console.log("tiles:", (el("tiles").innerHTML.match(/class="tile"/g)||[]).length);
console.log("devs chips:", (el("devs").innerHTML.match(/class="chip"/g)||[]).length);
for (const z of [3,6,11,22]) { ctx.setZ(z); ctx.draw();
  console.log(`Z=${z.toString().padStart(2)}  rows=${count('class="row"')} bars=${count('class="b ')} zebra=${count('class="zebra"')} months=${count('class="mon"')}`); }
ctx.setZ(11);
el("status").value = "done"; ctx.draw(); console.log("filter status=done → rows", count('class="row"'));
el("status").value = ""; el("crit").checked = true; ctx.draw(); console.log("filter critical → rows", count('class="row"'));
el("crit").checked = false; el("q").value = "ribbon"; ctx.draw(); console.log("search 'ribbon' → rows", count('class="row"'));
el("q").value = "zzzz"; ctx.draw(); console.log("search 'zzzz' → empty state:", g().includes("No tasks match"));
el("q").value = ""; ctx.devs.delete("A"); ctx.devs.delete("B"); ctx.draw();
console.log("only C+D → lanes", count('class="lane-h"'), "rows", count('class="row"'));

// label column width control
for (const w of [240, 360, 560]) {
  ctx.setLW(w); ctx.draw();
  const laneW = /class="lane-h" style="width:(\d+)px"/.exec(g());
  console.log(`LW=${w}  --lw=${store.__vars["--lw"]}  lane width=${laneW && laneW[1]}px`);
}
// no title may overflow its column: every label carries a title attribute and ellipsis CSS
const longest = Math.max(...ctx.TASKS.map(t => t.t.length));
console.log("longest task title:", longest, longest <= 68 ? "OK" : "TOO LONG");
