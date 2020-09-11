function setPriorities() {

var allNorm = document.getElementById("setallnorm");
var allHigh = document.getElementById("setallhigh");
var allSkip = document.getElementById("setallskip");

const setupbuttons=()=>{
  let sp = document.forms[0].savepri;
  if ( sp ) updatesetallbuttons(), sp.disabled = true, sp.className = 'disabled';

  var buttons = document.getElementsByClassName("prihigh");
  for(index = 0; index < buttons.length; index++)
  {
    var button = buttons[index];
      if (!button.disabled)
      addClickHandler(button);
  }
  buttons = document.getElementsByClassName("prinorm");
  for(index = 0; index < buttons.length; index++)
  {
    var button = buttons[index];
      if (!button.disabled)
      addClickHandler(button);
  }
  buttons = document.getElementsByClassName("priskip");
  for(index = 0; index < buttons.length; index++)
  {
    var button = buttons[index];
      if (!button.disabled)
      addClickHandler(button);
  }
  var button = allHigh;
  if (!button.disabled) {
    button.addEventListener("click", function() {
      setallhigh();
      event.preventDefault();
      });
  }
  button = allNorm;
  if (!button.disabled) {
    button.addEventListener("click", function() {
      setallnorm();
      event.preventDefault();
      });
  }
  button = allSkip;
  if (!button.disabled) {
    button.addEventListener("click", function() {
      setallskip();
      event.preventDefault();
      });
  }
}

const priorityclicked=()=>{
  let sp = document.forms[0].savepri;
  if ( sp ) updatesetallbuttons(), sp.disabled = false, sp.className = 'accept';
}

const updatesetallbuttons=()=>{
  let notNorm = true, notHigh = true, notSkip = true, i = 0, len, ele, elems = document.forms[0].elements;
  for( len = elems.length ; i < len && (notNorm || notHigh || notSkip) ; ) {
    ele = elems[i++];
    if (ele.type == 'radio' && !ele.checked) {
      if (ele.className == 'prinorm') notNorm = false;
      else if (ele.className == 'prihigh') notHigh = false;
      else notSkip = false;
    }
  }
  allNorm.className = notNorm ? 'controld' : 'control';
  allHigh.className = notHigh ? 'controld' : 'control';
  allSkip.className = notSkip ? 'controld' : 'control';
}

const setallnorm=()=>{
  let i = 0, ele, elems, len, form = document.forms[0];
  for ( elems = form.elements, len = elems.length ; i < len ; ) {
    ele = elems[i++];
    if (ele.type == 'radio' && ele.className === 'prinorm') ele.checked = true;
  }
  allNorm.className = 'controld';
  allHigh.className = 'control';
  allSkip.className = 'control';
  form.savepri.disabled = false;
  form.savepri.className = 'accept';
}

const setallhigh=()=>{
  let i = 0, len, ele, elems, form = document.forms[0];
  for( elems = form.elements, len = elems.length; i < len ; ) {
    ele = elems[i++];
    if (ele.type == 'radio' && ele.className === 'prihigh') ele.checked = true;
  }
  allNorm.className = 'control';
  allHigh.className = 'controld';
  allSkip.className = 'control';
  form.savepri.disabled = false;
  form.savepri.className = 'accept';
}

const setallskip=()=>{
  let i = 0, len, ele, elems, form = document.forms[0];
  for( elems = form.elements, len = elems.length; i < len ; ) {
    ele = elems[i++];
    if (ele.type == 'radio' && ele.className === 'priskip') ele.checked = true;
  }
  allNorm.className = 'control';
  allHigh.className = 'control';
  allSkip.className = 'controld';
  form.savepri.disabled = false;
  form.savepri.className = 'accept';
}

function addClickHandler(elem) {
  elem.addEventListener("click", function() {
    priorityclicked();
  });
}

}

document.addEventListener('mouseover', function() {
  setPriorities();
}, false);
