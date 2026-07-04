<%@page contentType="text/html" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" buffer="32kb"%>
<!DOCTYPE HTML>
<%@include file="head.jsi"%>
<%=intl.title("auto-tuning")%>
</head>
<body id=autotuning>
<%@include file="sidebar.jsi"%><h1 class=sched><%=intl._t("Auto-Tuning")%></h1>
<jsp:useBean class="net.i2p.router.web.TuningFormHandler" id="formhandler" scope="request"/>
<%@include file="formhandler.jsi"%>
<jsp:useBean class="net.i2p.router.web.helpers.TuningHelper" id="tuninghelper" scope="request"/>
<jsp:setProperty name="tuninghelper" property="contextId" value="<%=i2pcontextId%>"/>
<% tuninghelper.storeWriter(out); %>
<jsp:getProperty name="tuninghelper" property="tuning"/>
<script nonce=<%=cspNonce%>>
  document.getElementById('tuningform').nonce.value = '<%=pageNonce%>';

  // Auto-tuning checkbox toggle: when unchecked, replace Min/Max/Step inputs with mdash
  function initTuningToggles() {
    var rows = document.querySelectorAll('#tuningtable tr[data-prefix]');
    rows.forEach(function(row) {
      var cb = row.querySelector('.tuning-toggle');
      if (!cb) return;
      var minCell = row.querySelector('td.min');
      var maxCell = row.querySelector('td.max');
      var stepCell = row.querySelector('td.step');
      if (!minCell || !maxCell || !stepCell) return;

      // store original input HTML
      row.dataset.minHtml = minCell.innerHTML;
      row.dataset.maxHtml = maxCell.innerHTML;
      row.dataset.stepHtml = stepCell.innerHTML;

      function applyState() {
        if (cb.checked) {
          minCell.innerHTML = row.dataset.minHtml;
          maxCell.innerHTML = row.dataset.maxHtml;
          stepCell.innerHTML = row.dataset.stepHtml;
        } else {
          minCell.innerHTML = '&mdash;';
          maxCell.innerHTML = '&mdash;';
          stepCell.innerHTML = '&mdash;';
        }
      }

      cb.addEventListener('change', applyState);
      applyState();
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initTuningToggles);
  } else {
    initTuningToggles();
  }
</script>
<script src=/js/refreshElements.js type=module></script>
<script nonce=<%=cspNonce%> type=module>
  import {refreshElements} from "/js/refreshElements.js";
  refreshElements("#tuning", "/tuning", 5000);
</script>
</body>
</html>
