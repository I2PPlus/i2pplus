function applyFilter(){
var q=document.getElementById('filter').value.toLowerCase();
var hS=document.getElementById('hideCodeSmells').checked;
var hL=document.getElementById('hideInfo').checked;
var rows=document.querySelectorAll('#rules-body tr');
var c=0;
for(var i=0;i<rows.length;i++){
var r=rows[i];
var t=(r.getAttribute('data-key')+' '+r.getAttribute('data-name')+' '+r.textContent).toLowerCase();
var tp=r.getAttribute('data-type');
var s=r.getAttribute('data-sev');
var m=t.indexOf(q)!==-1;
if(hS&&tp==='CODE_SMELL')m=false;
if(hL&&(s==='INFO'||s==='MINOR'))m=false;
if(m){r.classList.remove('hidden');c++}else{r.classList.add('hidden')}
}
document.getElementById('count').textContent=c;
}
