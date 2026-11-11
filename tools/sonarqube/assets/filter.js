/**
 * @module filter
 * @file filter.js - Filter for the SonarQube rules report.
 * @description Hides the rows of the rules table that do not match the text in
 * the filter box, with check boxes to drop code smells and the informational
 * severities outright, and keeps the visible-row count beside them.
 * @author dr|z3d
 * @license AGPL3 or later
 */
function applyFilter(){
const q=document.getElementById('filter').value.toLowerCase();
const hS=document.getElementById('hideCodeSmells').checked;
const hL=document.getElementById('hideInfo').checked;
const rows=document.querySelectorAll('#rules-body tr');
let c=0;
for(let i=0;i<rows.length;i++){
const r=rows[i];
const t=(r.getAttribute('data-key')+' '+r.getAttribute('data-name')+' '+r.textContent).toLowerCase();
const tp=r.getAttribute('data-type');
const s=r.getAttribute('data-sev');
let m=t.indexOf(q)!==-1;
if(hS&&tp==='CODE_SMELL')m=false;
if(hL&&(s==='INFO'||s==='MINOR'))m=false;
if(m){r.classList.remove('hidden');c++}else{r.classList.add('hidden')}
}
document.getElementById('count').textContent=c;
}
