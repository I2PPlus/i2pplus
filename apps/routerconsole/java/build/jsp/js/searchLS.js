function searchLS(){var container=document.querySelector(".leasesets_container"),fragment=document.createDocumentFragment(),lsSearchDiv=document.createElement("div");container.style.columnCount="1",lsSearchDiv.id="searchLS";const searchInputDiv=document.createElement("div");searchInputDiv.id="searchLsInput",searchInputDiv.classList.add("fakeTextInput"),searchInputDiv.contentEditable=!0,searchInputDiv.setAttribute("spellcheck","false");var savedQuery=localStorage.getItem("LSquery");savedQuery&&(searchInputDiv.textContent=savedQuery);const lookupButton=document.createElement("a");lookupButton.className="fakebutton",lookupButton.textContent="Lookup",(savedQuery=document.createElement("style")).innerHTML=`
    #searchLS {
      padding: 8px;
      display: block;
      vertical-align: middle;
      text-align: center;
    }

    #searchLS div[contenteditable] {
      width: 40%;
      max-width: 500px;
      display: inline-block;
      vertical-align: middle;
      outline: none;
    }

    #searchLS a.fakebutton {
      display: inline-block;
      vertical-align: middle;
      text-decoration: none;
      transition: background-color 0.3s ease;
    }
  `,searchInputDiv.addEventListener("input",function(){var query=searchInputDiv.textContent.trim();localStorage.setItem("LSquery",query)}),lookupButton.addEventListener("click",function(){var searchInput=document.getElementById("searchLsInput"),query=searchInput.textContent.trim();""!==query&&(query="/netdb?ls="+encodeURIComponent(query),localStorage.removeItem("LSquery"),searchInput.textContent="",window.location.href=query)}),searchInputDiv.addEventListener("keydown",function(event){"Enter"===event.key&&(event.preventDefault(),lookupButton.click())}),lsSearchDiv.appendChild(searchInputDiv),lsSearchDiv.appendChild(lookupButton),fragment.appendChild(savedQuery),fragment.appendChild(lsSearchDiv),null===document.getElementById("searchLS")&&container.appendChild(fragment)}export{searchLS};