<%@page contentType="text/html"%>
<%@page trimDirectiveWhitespaces="true"%>
<%@page pageEncoding="UTF-8"%>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
<%
  /*
   *   Do not tag this file for translation - copy it to help_xx.jsp and translate inline.
   */
%>
<%
    net.i2p.I2PAppContext ctx = net.i2p.I2PAppContext.getGlobalContext();
    String lang = "es";
    if (ctx.getProperty("routerconsole.lang") != null)
        lang = ctx.getProperty("routerconsole.lang");
%>
<html lang="<%=lang%>">
<head>
<title>Ayuda - I2P+</title>
<%@include file="css.jsi" %>
<script src="/js/ajax.js" type="text/javascript"></script>
</head>
<body>
<script nonce="<%=cspNonce%>" type="text/javascript">progressx.show();</script>
<%@include file="summary.jsi" %>
<h1 class="hlp">Help &amp; Support</h1>
<div class="main" id="help">

<div class="confignav">
<span class="tab"><a href="#sidebarhelp">Barra lateral</a></span>
<span class="tab"><a href="#configurationhelp">Configuración</a></span>
<span class="tab"><a href="#reachabilityhelp">Accesibilidad</a></span>
<span class="tab"><a href="#reseedhelp">Resembrado</a></span>
<span class="tab"><a href="#advancedsettings">Configuración avanzada</a></span>
<span class="tab"><a href="#faq">FAQ</a></span>
<span class="tab"><a href="#troubleshoot">Solución de problemas</a></span>
<span class="tab"><a href="#legal">Legal</a></span>
<span class="tab"><a href="#changelog">Registro de cambios</a></span>
</div>

<div id="volunteer">
<h2>Ayuda adicional</h2>
<p>Si deseas ayudar a mejorar o a traducir la documentación, o deseas ayudar en otros aspectos del proyecto, mira la documentación para los <a href="http://i2p-projekt.i2p/en/get-involved" target="_blank" rel="noreferrer">voluntarios.</a></p>
<p>Hay más ayuda disponible aquí:</p>
<ul class="links">
<li><a href="http://i2pforum.i2p/" target="_blank" rel="noreferrer">Foro de soporte de I2P</a></li>
<li><a href="http://zzz.i2p/" target="_blank" rel="noreferrer">Foro de los desarrolladores de I2P</a></li>
<li><a href="http://wiki.i2p-projekt.i2p/" target="_blank" rel="noreferrer">I2P Wiki</a></li>
<li>The FAQ on <a href="http://i2p-projekt.i2p/en/faq" target="_blank" rel="noreferrer">i2p-projekt.i2p</a> o <a href="https://geti2p.net/en/faq" target="_blank" rel="noreferrer">geti2p.net</a></li>
</ul>
<p>También puedes probar en la <a href="irc://127.0.0.1:6668/i2p">red IRC de I2P</a>.</p>
</div>

<div id="sidebarhelp">
<h2>Información sobre la barra de resumen</h2>

<p>
La mayoría de las estadísticas en la barra de resumen pueden ser <a href="configstats">configuradas</a> para crear <a href="graphs">gráficas</a> para analizar más tarde. En la <a href="configsidebar">página de configuración de la barra de resumen</a> también puedes configurar las secciones que aparecen en la barra y sus posiciones en ella.
</p>

<h3>Información del Rúter</h3>

<ul>

<li>
<b>Identidad Local:</b>
Si pasas el cursor sobre la cabecera <i>Información del Rúter</i>, se mostrará la identidad recortada de tu rúter (los primeros caracteres (24 bits) del hash Base64 de tu rúter de 44 caracteres). El hash completo se muestra en la <a href="netdb?r=.">entrada de la NetDb</a>. Nunca reveles este dato a nadie, ya que la identidad del rúter es única y está ligada a tu dirección IP en la base de datos de la red.</li>

<li>
<b>Versión:</b>
La versión de I2P que estás ejecutando. Si hubiese una nueva versión disponible serás notificado en esta barra. Es recomendable mantener el rúter actualizado para asegurar el mayor rendimiento y seguridad. Las actualizaciones del rúter suelen estar disponibles cada 2 ó 3 meses.
</li>

<li>
<b>Desajuste en el reloj:</b>
Indica el desajuste (de-sincronización) del reloj de tu computadora con respecto al tiempo online (si se conoce). I2P necesita que la fecha en tu computadora sea precisa. Si el desajuste es mayor de unos segundos, por favor, arregla el problema ajustando la fecha en tu computadora. Si I2P no puede conectarse a Internet, se indicarán 0ms. Nota: Esto sólo se muestra en la sección <i>Información del rúter (advanzado)</i>. Puedes añadir esta sección a tu barra de resumen en la <a href="configsidebar">página de resumen de la barra de configuración</a>.
</li>

<li>
<b>Memoria:</b>
Muestra la cantidad de RAM que I2P está usando, y el total disponible asignado por Java. Si el uso es alto en relación con la RAM disponible, puede indicar que necesites aumentar la ram asignada a la JVM. Puedes asignar más RAM editando el archivo <code>wrapper.config</code> que está localizado usualmente en el directorio de la aplicación I2P. Tienes que editar el parámetro <code>wrapper.java.maxmemory</code> que por defecto está a 128(MB).  <!--<b>Nota:</b> El uso de la memoria sólo se muestra en la sección <i>Información del rúter (advanzado)</i> en la <i>Barra del uso de memoria</i>, aunque puedes añadir ambas a la barra de resumen en la <a href="configsidebar">página de resumen de la barra de configuración</a>..-->
</li>

</ul>

<h3>Pares</h3>

<ul>

<li>
<b>Activos:</b> El primer número indica el número de pares a los que tu rúter ha enviado o recivido mensajes en los últimos minutos. Esto puede viaria entre 8-10 hasta varios cientos, dependiendo del ancho de banda total, el ancho de banda compartido y el tráfico generado localmente. El segundo número indica el número de pares vistos en la última hora. Tampoco te preocupes mucho si estos números cambian mucho. <a href="configstats#router.activePeers">[Habilitar gráficas]</a>.
</li>

<li>
<b>Rápidos:</b>  Este es el número de pares que tiene disponible tu rúter para crear túneles cliente. Suele estar entre el rango de 8-30. Tus pares rápidos se muestran en la <a href="profiles">Página de Perfiles</a>. <a href="configstats#router.fastPeers">[Habilitar gráficas]</a>
</li>

<li>
<b>Alta capacidad:</b>
Este es el número de pares que tu rúter tiene disponibles para construir túneles exploratorios que son usados para determinar el rendimiento de la red. Suele estar en el rango de 8-75. Los pares rápidos están incluidos en el nivel de alta capacidad. Tus pares de alta capacidad se muestran en la <a href="profiles">Página de Perfiles</a>. <a href="configstats#router.fastPeers">[Habilitar gráficas]</a>
</li>

<li>
<b>Integrados:</b> Es es el número de pares que tu rúter usará para las peticiones a la NetDb. Son usualmente los rúters "floodfill" los que están encargados de mantener la integridad de la red. Los pares bien integrados se muestran en la parte de abajo de la <a href="profiles">Página de Perfiles</a>.
</li>

<li>
<b>Conocidos:</b> Este es el número total de pares que tu rúter conoce. Se listan en la <a href="netdb">Página de la NetDb</a>. Este valor puede variar de 100 a 1000 o más. El número no indica el tamaño total de la red; este valor puede variar enormemente dependiendo de tu ancho de banda total, el ancho de banda compartido, y el tráfico generado localmente. I2P no necesita que un rúter conozco a todos los rúters de la red.
</li>

</ul>

<h3>Ancho de banda In/Out</h3>

<p>
Esta sección muestra las velocidades medias y el ancho de banda total usado en esta sesión. Todos los valores se muestran en bytes por segundo. Puedes cambiar los límites del ancho de banda en la <a href="config">Página de configuración del ancho de banda</a>. Cuanto más ancho de banda compartas, más ayudarás a la red y mejorarás tu anonimato, por lo que, por favor, tómate tu tiempo para revisar estas configuraciones. Si no estás seguro de la velocidad de tu red, puedes usar un servicio como  <a href="http://speedtest.net/">SpeedTest</a> o similar. Tu velocidad de subida (KBps de salida) determinará cuánto contribuyes a la red. El ancho de banda crea <a href="graphs">gráficas</a> por defecto.
</p>

<h3>Destinaciones locales</h3>

<p>
Estos son los servicios locales proporcionados por tu rúter. Pueden ser clientes iniciados por el <a href="i2ptunnelmgr">Administrador de Túneles</a> o aplicaciones externas que usen SAM, BOB o I2CP directamente. Por defecto, la mayoría de tus clientes (correo, proxy http, IRC) compartirán los mismos túneles y se mostrarán como <i>Clientes Compartidos</i> y <i>Clientes Compartidos(DSA)</i>, lo que aumente el anonimato al unir el tráfico de todos los servicios compartidos, y reduce los recursos necesarios para construir y mantener dichos túneles. Aunque, si un túnel falla, todos los servicios fallarán al mismo tiempo, por lo que en algunos escenarios preferirás configurar los servicios cliente para que usen sus propios túneles. Esto puede hacerse desmarcando la opción <i>Compartir túneles con otros clientes</i> mostrada bajo <i>Clientes compartidos</i> en la página de configuración del cliente deseado en el Administrador de Túneles, después de lo que tendrás que reiniciar el servicio cliente desde la <a href="i2ptunnelmgr">página principal del Administrador de Túneles</a>.
</p>

<h3>Túneles</h3>

<p>
Los túneles activos se muestran en la <a href="tunnels">Página de túneles</a>.
</p>

<ul>

<li>
<b>Exploratorios:</b> Los túneles creados por tu rúter y usados para comunicarse con los pares floodfill, para construir nuevos túneles y para probar los túneles ya existentes.
</li>

<li>
<b>Clientes:</b> Túneles creados por tu rúter para el uso de cada cliente.
</li>

<li>
<b>Participantes:</b> Túneles creados por otros rúters a través de tu rúter. Este valor puede cambiar mucho dependiendo de las demandas de la red, tu ancho de banda compartido y la cantidad de tráfico generado localmente. El método recomendado para limitar los túneles participantes es cambiando ancho de banda compartido en la <a  href="config">Página de configuración del ancho de banda</a>. También puedes limitar el número total configurando <code>router.maxParticipatingTunnels=nnn</code> en la <a href="configadvanced">Página de la configuración avanzado</a>. <a href="configstats#tunnel.participatingTunnels">[Activar Gráfica]</a>.
</li>

<li>
<b>Tasa de compartición:</b> El número de túneles participantes que enrutas para otros, dividido por el número total de saltos en todos tus túneles exploratorios y cliente. Un número mayor de 1.00 significa que contribuyes con más túneles a la red de los que usas.
</li>

</ul>

<h3>Congestión</h3>

<p>
Indicaciones básicas de la sobrecarga del rúter:
</p>

<ul>

<li>
<b>Retardo de los trabajos:</b> Indica cuánto están tardando los trabajos antes de  ejecutarse. La cola de trabajos se muestra en la <a href="jobs">página de los Trabajos</a>. Desafortunadamente, hay otras colas de trabajos en el rúter que podrían estar congestionadas, y su estado no está disponible en la consola del rúter. El retardo de los trabajos debe ser generalmente cero. Si es mayor de 500ms a menudo, tu computadora es muy lenta, tienes problemas de conexión en la red o el rúter tiene serios problemas. <a href="configstats#jobQueue.jobLag">[Activar gráfica]</a>.
</li>

<li>
<b>Retardo de los mensajes:</b> Muestra por cuánto tiempo espera un mensaje de salida en la cola. usualmente deben ser unos pocos cientos de milisegundos o menos. Si es a menudo mayor de 1000ms, tu computadora es lenta, o tienes que ajustar los límites del ancho de banda o tus clientes (¿Bittorrent?) están enviando demasiados datos y deberían tener el límite de transferencia del ancho de banda limitado. <a href="configstats#transport.sendProcessingTime">[Activar Gráfica]</a> (transport.sendProcessingTime).
</li>

<li>
<b>Atrasos:</b> Este es el número de peticiones pendientes de otros rúters para construir túneles participantes a través de tu rúter. Usualmente debe estar cercano a cero. Si es alto a menudo, tu computadora es lenta y deberías reducir el ancho de banda compartido.
</li>

<li>
<b>Aceptando/Denegando:</b> Muestra el estado de tu rúter sobre la denegación o la aceptación de peticiones de otros rúters para crear túneles participantes a través de tu rúter. Tu rúter puede que acepte todas las peticiones, que acepte o deniegue un porcentage de las peticiones o que deniegue todas las peticiones por varias razones; como controlar el ancho de banda, la demanda de CPU y para mantener la actividad para los clientes locales. <b>Nota:</b> Tu rúter tardará al menos 10 minutos después de arrancar para aceptar y crear túneles participantes para asegurarse de que tu rúter es estable y que se ha integrado en la red satisfactoriamente.
</li>

</ul>

<p>
<b>Nota:</b> Esta sección no está activada por defecto a no ser que se active el <a href="#advancedconsole">modo Avanzado de la Consola</a>. Puedes activarlo en la <a href="configsidebar">página de Configuración de la Barra de Resumen</a>.
</p>
</div>

<div id="configurationhelp"><%@include file="help-configuration.jsi" %></div>
<div id="reachabilityhelp"><%@include file="help-reachability.jsi" %></div>
<div id="reseedhelp"><%@include file="help-reseed.jsi" %></div>
<div id="advancedsettings">
<h2>Configuración avanzada del rúter</h2>

<p class="infohelp">Las opciones de configuración del rúter listadas abajo no están disponibles en el interfaz de usuario, normalmente porque son usadas muy pocas veces o porque proporcionan acceso a configuraciones avanzadas que la mayoría de usuarios no necesita. Esto no es una lista completa. Algunas configuraciones necesitarán reiniciar el rúter para aplicarse. Date cuenta que todas las configuraciones son sensibles a mayúsculas y minúsculas. Tendrás que editar el archivo <code>router.config</code> para añadir opciones, o, una vez que hayas añadido <code>routerconsole.advanced=true</code> en el archivo router.config, podrás editar las configuraciones desde la consola en la <a href="/configadvanced">Página de configuración avanzada</a>.</p>

<table id="configinfo"> <!-- sections separated for legibility -->

<tr><th id="advancedconsole">routerconsole.advanced={true|false}</th></tr>
<tr><td class="infowarn">¡Activa esta opción sólo si sabes lo que estás haciendo!</td></tr>
<tr><td>Cuando está activado, el usuario podrá editar las opciones adicionales directamente en la <a href="/configadvanced">Página de configuración avanzada</a>. Opciones adicionales de visualización estarán disponibles en la <a href="/netdb">Sección de la base de datos de red, netDb</a>, incluyendo la <a href="/netdb?f=3">herramienta de análisis de Sybil</a>, y habrá opciones adicionales de configuración en la <a href="/configclients">Página de configuración de los clientes</a>. Esto también activa la instalación de actualizaciones no firmadas, la configuración manual de la URL de noticias y secciones adicionales en la barra lateral.</td></tr>

<tr><th>routerconsole.allowUntrustedPlugins={true|false}</th></tr>
<tr><td>El formato recomendado las extensiones firmadas con la clave criptográfica del desarrollador, pero si quieres instalar extensiones no firmadas (.zip) puedes activar esta opción. Date cuenta que aún puedes encontrar problemas al intentar instalar extensiones sin firmar si el desarrollador ha incluido comprobaciones adicionales en el proceso de compilado.</td></tr>

<tr><th>routerconsole.browser={/path/to/browser}</th></tr>
<tr><td>Estas opciones permiten la selección manual del navegador que I2P ejecutará al inicio (si la consola has sido <a href="/configservice#browseronstart">configurada</a> para iniciar el navegador al arrancar), anulando al uso del navegador por defecto del sistema. Para más información mira el <a href="#alternative_browser">FAQ</a> más arriba.</td></tr>

<tr><th>routerconsole.enableCompression={true|false}</th></tr>
<tr><td>Cuando está activado permite la compresión gzip para la consola del rúter y las aplicaciones web por defecto. [Activo por defecto]</td></tr>

<tr><th>routerconsole.enablePluginInstall={true|false}</th></tr>
<tr><td>uando está activado permite la instalación de extensiones en la <a href="/configplugins">Página de configuración de extensiones</a>. [Activo por defecto]</td></tr>

<tr><th>routerconsole.redirectToHTTPS={true|false}</th></tr>
<tr><td>Cuando está activado redireccionará automáticamente a https cuando se acceda vía http:// a la consola del rúter. Para configurar el acceso a la consola del rúter, edita las configuraciones en el archivo <code>clients.config</code> en el directorio de tu perfil (<i>no</i> en el directorio de instalación). [Activo por defecto]</td></tr>

<!--
<tr><th>routerconsole.showSearch={true|false}</th></tr>
<tr><td>Cuando está activado aparecerá una barra de búsqueda en la <a href="/home">página principal de la consola</a>. Y se pueden añadir búsquedas adicionales en la <a href="/confighome">Página de configuración de la página principal</a>.</td></tr>
-->

<tr><th>router.buildHandlerThreads={n}</th></tr>
<tr><td>El número de hilos asignados para construir túneles. Si tu procesador soporta hyperthreading o multithreading simultáneo, puedes multiplicar el número de procesadores por 2 para obtener el máximo número de hilos a asignar, si no, el número de procesadores = al número de hilos disponibles. Puedes asignar si lo deseas un número menor que el máximo teórico para asegurarte de que dejas espacio para otras tareas.</td></tr>

<tr><th>router.excludePeerCaps={netDBcaps}</th></tr>
<tr><td>Esta opción determina qué <a href="/profiles#profile_defs">funciones del par</a> no se usarán para construir los túneles del rúter. por ejemplo<code>router.excludePeerCaps=LMN</code></td></tr>

<tr><th>router.hideFloodfillParticipant={true|false}</th></tr>
<tr><td>Si está activado, si tu rúter es de tipo floodfill en la red, tu <a href="/configadvanced#ffconf">participatión como floodfill</a> se ocultará a otros rúters.</td></tr>

<tr><th>router.maxJobRunners={n}</th></tr>
<tr><td>Define el número máximo de <a href="/jobs">trabajos</a> en paralelo que pueden ejecutarse. El valor por defecto está determinado por la cantidad de memoria asignada e la JVM en <code>wrapper.config</code>, y se pone a 3 si es menor de 64MB, a 4 para menos de 256MB o a 5 o más para más de 256MB. Nota: Un cambio en esta opción requiere un reinicio del rúter para aplicarse.</td></tr>

<tr><th>router.maxParticipatingTunnels={n}</th></tr>
<tr><td>Determina el número máximo de túneles participantes que el rúter puede construir. Para deshabilitar totalmente la participación, ponlo en 0. [Configurado por defecto automáticamente]</td></tr>

<tr><th>router.networkDatabase.flat={true|false}</th></tr>
<tr><td>Si está activado, los archivos de información del rúter almacenados en el directorio del perfil de la netDB no será dividido en 64 subdirectorios.</td></tr>

<tr><th>router.updateUnsigned={true|false}</th></tr>
<tr><td>Si deseas instalar actualizaciones de I2P sin firmar (.zip), debes añadir esto a tu archivo <code>router.config</code>, a no ser que ya hayas configurado <code>routerconsole.advanced=true</code>, en tal caso esta opción ya está activada.</td></tr>

<tr><th>router.updateUnsignedURL={url}</th></tr>
<tr><td>Esta opción, si se ha activado, te permite configurar la URL de actualización para los actualizaciones no firmadas. La URL debe terminar en <code>/i2pupdate.zip</code>. Nota: ¡No instales actualizaciones sin firmar a no ser que confíes en la fuente de la actualización!</td></tr>

<tr><th>i2p.vmCommSystem={true|false}</th></tr>
<tr><td>Si está activado, I2P funcionará sin conexión de red, lo que es útil si estás reiniciando el rúter constantemente para probar actualizaciones y así evitar perturbar la red.</td></tr>

<tr><th>router.expireRouterInfo={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en horas) determina cuán viejo es un RouterInfo en la NetDb (su última fecha de publicación) antes de ser clasificado como viejo y ser eliminado. [Por defecto son 24 horas a no ser que sea un Floodfill, en cuyo caso por defecto son 8 horas]</td></tr>

<tr><th>router.explorePeersDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en segundos) permite sobrescribir el retardo entre ejecuciones del trabajo de Exploración de Pares, el cual intenta localizar nuevos pares para añadirlos a la NetDb. Esta tarea se ejecuta por defecto cada 80 segundos, o si el rúter tiene retrasos en los mensajes o en las tareas, cada 3 minutos. Puedes querer aumenta el retardo si tu NetDb está bien poblada (más de 2000 pares), o si deseas disminuir el uso general del ancho de banda.</td></tr>

<tr><th>router.refreshRouterDelay={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en milisegundos) te permite configurar manualmente el retardo entre las actualizaciones de refresco del rúter  ejecutadas por el Trabajo de Refresco del Rúter. Por defecto las pausas entre refrescos están determinadas por el tamaño de la NetDb, e introduce algo de aleatoriedad en el cronometraje para mitigar el análisis del tráfico. Para los valores menores a 2000 milisegundos, se recomienda aumentar el valor de <code>router.refreshTimeout</code>. Date cuenta que ajustar este valor por debajo de 2000 milisegundos aumentará tu tráfico de red y podría crear un retraso en los trabajos y no se recomienda para un uso continuado.</td></tr>

<tr><th>router.refreshSkipIfYounger={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en horas) te permite configurar manualmente cuán viejo es un RouterInfo para ser comprobado por el Trabajo de Refresco del Rúter. Por defecto, la edad de un RouterInfo antes de un refresco es iniciada de acuerdo con el tamaño de la NetDb, aumentando al mismo tiempo que la NetDb crece en tamaño. Un valor de 0 forzará al Trabajo de Refresco del rúter a comprobar todos los rúters en la NetDb, independientemente de su edad.</td></tr>

<tr><th>router.refreshTimeout={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en segundos) te permite configurar manualmente la cantidad de tiempo a esperar antes de que el intento de refrescar un rúter sea marcado como fallido. [Por defecto son 20 segundos]</td></tr>

<tr><th>router.validateRoutersAfter={n} <span class="plus">I2P+</span></th></tr>
<tr><td>Esta opción (en minutos) te permite configurar manualmente cuánto esperar para comprobar la validez de los RouterInfos en la NetDb después del inicio del rúter, y tras dicho punto, sólo los rúters válidos serán aceptados para incluirse. Cuando ocurre la validación, los RouterInfos ya expirados y los pares inalcanzables sólo accesibles vía SSU, serán eliminados de la NetDb. [Por defecto 60 minuitos] Nota: Esta opción no se aplica a los rúters viejos (anteriores a la 0.9.30) ya que son eliminados de la NetDb y baneados por el rúter en el momento en que se intenta un almacenamiento en la NetDb.</td></tr>

</table>

</div>

<div id="faq">

<h2>PUF de I2P resumido</h2>

<p class="infohelp">Esto es una versión resumida del FAQ oficial. Para ver la versión completa ves a <a href="https://geti2p.net/faq" target="_blank" rel="noreferrer">https://geti2p.net/faq</a> o a <a href="http://i2p-projekt.i2p/faq" target="_blank" rel="noreferrer">http://i2p-projekt.i2p/faq</a>.</p>

<h3>Mi rúter lleva encendido varios minutos y tiene ninguna o casi ninguna conexión</h3>

<p>Si después de varios minutos funcionando, tu rúter dice que tiene 0 pares activos y 0 conocidos, con una notificación en la barra lateral de que debes revisar tu conexión, verifica que tienes acceso a Internet. Si tu conexión a Internet funciona, quizás tienes que permitir a Java en tu firewall. O si no, quizás debas resembrar el rúter I2P. Ves a la <a href="/configreseed#reseedconfig">página de Configuración de resembrado</a> y pulsa en <i>Guardar cambios y resembrar ahora</i>. Para más información mira en la <a href="#reseedhelp">sección de ayuda de resembrado</a> de más arriba.</p>

<h3>Mi rúter tiene muy pocos pares activos, ¿Esto está bien?</h3>

<p>Si tu rúter tiene 10 o más pares activos, todo va bien. El rúter debe mantener las conexiones a unos pocos rúters todo el tiempo. La mejor forma de tener una "conexión mejor" con la red es <a href="/config">compartiendo más ancho de banda</a>.</p>

<h3 id="addressbooksubs">Me faltan muchos dominios en mi libreta de direcciones. ¿Hay buenos enlaces de suscripciones?</h3>

<p>La suscripción por defecto es a <code>http://i2p-projekt.i2p/hosts.txt</code>, que se auto actualiza automáticamente. Si no tienes otras suscripciones tendrás que usar los enlaces de 'saltos' que son más lentos pero te asegurarás de que en tu libreta de direcciones sólo tienes las webs que utilizas (además de las direcciones por defecto). Para acelerar la navegación en I2P es una buena idea añadir algunas suscripciones a la libreta de direcciones.</p>

<p>Aquí tienes algunos otros enlaces de suscripciones públicas para la libreta de direcciones. Quizás quieras añadir una o dos a tu <a href="/susidns/subscriptions" target="_blank" rel="noreferrer">lista de suscripciones de susidns</a>. En el caso de que las direcciones entre en conflicto con las suscripciones, las listas situadas arriba de tu configuración de susidns tendrán preferencia sobre las que estén colocadas debajo.</p>

<ul>
<li><code>http://stats.i2p/cgi-bin/newhosts.txt</code></li>
<li><code>http://skank.i2p/hosts.txt</code></li>
<li><code>http://notbob.i2p/hosts.txt</code></li>
<li><code>http://reg.i2p/export/hosts.txt</code></li>
<li><code>http://identiguy.i2p/hosts.txt</code></li>
</ul>

<p>Fíjate que el suscribirse a un servicio host.txt es un acto de confianza, ya que una suscripción maliciosa podría darte una dirección incorrecta, por lo que ten cuidado al suscribirte a listas de fuentes desconocidas. Los operadores de estos servicios pueden tener sus propias reglas al listar los dominios. La presencia de un servicio en una lista no implica su aprobación.</p>

<h3>¿Cómo accedo a un servicio IRC, BitTorrent u otros servicios en el Internet normal?</h3>

<p>A no ser que hayas configurado un outproxy para el servicio al que deseas conectar, no es posible, con la excepción del protocolo Bittorrent (ver más abajo). Ahora mismo sólo hay 3 tipos de outproxies funcionando; HTTP, HTTPS y email. Date cuenta que actualmente no hay listado públicamente ningún outproxy SOCKS. Si necesitas este tipo de servicio, prueba con <a href="https://torproject.org/" target="_blank" rel="noreferrer">Tor</a>.</p>

<h3>¿Puedo descargar torrents desde trackers que no sean de I2P?</h3>

<p>Hasta que <a href="http://www.vuze.com/" target="_blank" rel="noreferrer">Vuze</a> no integró I2P, no era posible descargar torrents que no estuviesen registrados en los trackers de I2P. Aunque, ahora que Vuze (y más tarde <a href="https://www.biglybt.com/" target="_blank" rel="noreferrer">Bigly</a>) permiten a los usuarios compartir torrents de fuera de I2P en I2P, se pueden descargar torrents de fuera de I2P si los usuarios de Vuze/Bigly han configurado dichas apps para hacer disponible el contenido en la red I2P. Los torrents populares con un gran número de pares son más fáciles de acceder desde I2P; para comprobar si un contenido está disponible, simplemente añade el enlace torrent, margent o infohash a tu cliente de torrents con I2P activado.</p>

<h3 id="alternative_browser">¿Cómo configuro I2P para abrir al inicio un navegador web determinado?</h3>

<p>Por defecto I2P, al iniciarse, usará el navegador por defecto configurado en el sistema. Si deseas indicar un navegador alternativo tendrás que editar el archivo router.config ( o la <a href="/configadvanced">página de Configuración Avanzada</a> si has activado el modo avanzado en la consola) y añadir una nueva línea de configuración <code>routerconsole.browser=/path/to/browser</code> o si usas windows <code>routerconsole.browser=\path\to\browser.exe</code>, reemplazando <code>/path/to/browser</code> con la localización del navegador que quieras usar. Nota: So el destino elegido contiene espacios, tendrás que encapsular el destino entre comillas, por ejemplo <code>routerconsole.browser="C:\Program Files\Mozilla\Firefox.exe"</code></p>

<h3>¿Cómo configuro mi navegador para acceder eepsites .i2p?</h3>

<p>Necesitas configurar tu navegador para usar el proxy HTTP (por defecto en la IP: <code>127.0.0.1</code> puerto: <code>4444</code>). Para más detalles mira en <a href="https://geti2p.net/en/about/browser-config" target="_blank" rel="noreferrer">Guía de configuración del proxy en el navegador</a>.</p>

<h3>¿Qué es una eepsite?</h3>

<p>Una eepsite es una web que está hospedada anónimamente en la red I2P - puedes acceder a ellas configurando tu navegador web para que use el proxy HTTP de I2P (mirar más arriba) y navegar por webs con el sufijo <code>.i2p</code> (por ejemplo <a href="http://i2p-projekt.i2p" target="_blank" rel="noreferrer">http://i2p-projekt.i2p</a>). Asegúrate que el nabegador está configurado para resolver los DNS remotamente cuando use el proxy y así evitar fugas de DNS.</p>

<h3>¿La mayoría de las eepsites no funcionan?</h3>

<p>Si consideras cada eepsite que se ha creado, sí, la mayoría están caídas. La gente y sus webs van y viene. Una buena forma de iniciarse con I2P es usar una lista de eepsites que están funcionando. <a href="http://identiguy.i2p" target="_blank" rel="noreferrer">http://identiguy.i2p</a> lleva la cuenta de las eepsites activas.</p>

<h3>¿Cómo me conecto al IRC dentro de I2P?</h3>

<p>Cuando instalas I2P se crea un túnel al servidor IRC principal detro de I2P, Irc2P  (ver el <a href="/i2ptunnelmgr">Administrador de túnelesr</a>), y se inicia automáticamente cuando I2P se inicia. Para conectarse a él, dile a tu cliente de IRC que se conecte al servidor:<code>127.0.0.1</code> puerto: <code>6668</code>.</p>

<p>Los clientes tipo XChat pueden crear una nueva red con el servidor <code>127.0.0.1/6668</code> (recuerda activar <i>Bypass proxy server</i> si ya tenías un servidor proxy configurado), o puedes conectarte con el comando <code>/server 127.0.0.1 6668</code>. Los diferentes clientes IRC pueden tener su propia sintaxis.</p>

<h3>¿Qué puertos usa I2P?</h3>

<table id="portfaq">

<tr><th colspan="3">PUERTOS LOCALES</th></tr>

<tr><td colspan="3" class="infohelp">Estos son los puertos locales de I2P, escuchando por defecto únicamente a las conexiones locales, excepto cuando se indique. A no ser que requieras acceder desde otras máquinas, deberían estar accesible sólo desde localhost.</td></tr>

<tr><th>Puerto</th><th>Función</th><th>Notas</th></tr>

<tr><td>1900</td><td>UPnP SSDP UDP multicast listener</td><td>No puede cambiarse. Escucha en todos los dispositivos. Puede desactivarse en la <a href="/confignet">Página de configuración de la red</a>.</td></tr>

<tr><td>2827</td><td>Puente BOB</td><td>Un socket API de alto nivel para clientes. Puede activarse/desactivarse en la <a href="/configclients">página de Configuración de los Clientes</a>. Puede cambiarse en el archivo <code>bob.config</code>. [Desactivado por defecto]</td></tr>

<tr><td>4444</td><td>Proxy HTTP</td><td>Puede ser desactivado o cambiado en el <a href="/i2ptunnelmgr">Gestor de Túneles</a>. También puede configurarse para escuchar en un dispositivo o en todos.</td></tr>

<tr><td>4445</td><td>Proxy HTTPS</td><td>Puede ser desactivado o cambiado en el <a href="/i2ptunnelmgr">Gestor de Túneles</a>. También puede configurarse para escuchar en un dispositivo o en todos.</td></tr>

<tr><td>6668</td><td>Proxy IRC</td><td>Puede ser desactivado o cambiado en el <a href="/i2ptunnelmgr">Gestor de Túneles</a>. También puede configurarse para escuchar en un dispositivo o en todos.</td></tr>

<tr><td>7652</td><td>UPnP HTTP TCP event listener</td><td>Escucha en la IP de la LAN. Puede cambiarse con la configuración avanzada <code>i2np.upnp.HTTPPort=nnnn</code>. Puede desactivarse en la <a href="/confignet">Página de Configuración de la Red</a>.</td></tr>

<tr><td>7653</td><td>UPnP SSDP UDP search response listener</td><td>Escucha en todos los dispositivos. Puede cambiarse con la configuración avanzada<code>i2np.upnp.SSDPPort=nnnn</code>. Puede desactivarse en la <a href="/configclients">página de Configuración de los Clientes</a>.</td></tr>

<tr><td>7654</td><td>I2P Client Protocol port</td><td>Usado por la apps cliente. Puede cambiarse a un puerto diferente en la <a href="/configclients">página de Configuración de los Clientes</a> pero no se recomienda cambiarlo. Puede escuchar en un interfaz o en todos, o desactivarlo, en la <a href="/configclients">página de Configuración de los Clientes</a>.</td></tr>

<tr><td>7655</td><td>UDP para el puente SAM</td><td>Un API socket de alto nivel para clientes. Sólo se abre cuando un cliente SAM v3 pide una sesión UDP. Puede activarse/desactivarse en la <a href="/configclients">página de Configuración de los Clientes</a>. Puede cambiarse en el archivo <code>clients.config</code> con la opción de línea de comandos de SAM <code>sam.udp.port=nnnn</code>.</td></tr>

<tr><td>7656</td><td>Puente SAM</td><td> Un API socket de alto nivel para clientes. Puede activarse/desactivarse en la <a href="/configclients">página de Configuración de los Clientes</a>. Puede cambiarse en el archivo <code>clients.config</code>. [Desactivado por defecto]</td></tr>

<tr><td>7657</td><td>La consola de rúter de I2P (interfaz Web)</td><td> Puede desactivarse en el archivo <code>clients.config</code>. También puede ser configurada para escuchar en un interfaz determinado o en todos. Si pones disponible la Consola del Rúter en la red, deberías <a href="/configui#passwordheading">poner una contraseña</a> para prevenir accesos no autorizados.</td></tr>

<tr><td>7658</td><td>Servidor Web de I2P</td><td>Puede desactivarse en el archivo <code>clients.config</code>. También puede ser configurado para escuchar en un interfaz específico o en todos en el archivo <code>jetty.xml</code>.</td></tr>

<tr><td>7659</td><td>Correo de salida a smtp.postman.i2p</td><td>Puede desactivarse o cambiarse en el <a href="/i2ptunnelmgr">Administrador de Túneles</a>. También puede ser configurado para escuchar en un interfaz específico o en todos.</td></tr>

<tr><td>7660</td><td>Correo entrante desde smtp.postman.i2p</td><td>Puede desactivarse o cambiarse en el <a href="/i2ptunnelmgr">Administrador de Túneles</a>. También puede ser configurado para escuchar en un interfaz específico o en todos.</td></tr>

<tr><td>7667</td><td>Consola del Rúter de I2P (https://)</td><td>Puede activarse en el archivo <code>clients.config</code>. También puede ser configurado para escuchar en un interfaz específico o en todos. Si ambos, htt y https, están activos, se redireccionará automáticamente a https. Si pones la Consola del Rúter disponible en la red, deberías <a href="/configui#passwordheading">poner una contraseña</a> para prevenir accesos no autorizados.</td></tr>

<tr><td>8998</td><td>mtn.i2p2.i2p (Monotone DVCS de I2P)</td><td>Puede desactivarse o cambiarse en el <a href="/i2ptunnelmgr">Administrador de Túneles</a>. También puede ser configurado para escuchar en un interfaz específico o en todos. [Desactivado por defecto]</td></tr>

<tr><td>31000</td><td>Puerto local para el control del wrapper</td><td>Sólo de salida hacia 32000, no escucha en este puerto. Se incia en 31000 y se incrementa hasta el 31999 buscando por un puerto libre. Para cambiarlo, mira la <a href="http://wrapper.tanukisoftware.com/doc/english/prop-port.html" target="_blank" rel="noreferrer">documentación del wrapper</a>.</td></tr>

<tr><td>32000</td><td>Puerto local para el control del servicio wrapper</td><td>Para cambiarlo, mira la <a href="http://wrapper.tanukisoftware.com/doc/english/prop-port.html" target="_blank" rel="noreferrer">documentación del wrapper</a>.</td></tr>

<tr><th colspan="3">PUERTOS ESCUCHANDO en INTERNET</th></tr>

<tr><td colspan="3" class="infohelp">I2P selecciona un puerto aleatorio entre 9000 y 31000 para comunicarse con otros rúters cuando el programa se ejecuta por primera vez, o cuando cambia tu IP externa cuando se ejecuta en <a href="/confignet#ipchange">Laptop Mode</a>. El <a href="/confignet#udpconfig">puerto seleccionado</a> se muestra en la <a href="/confignet">página de Configuración de la Red</a>.</td></tr>

<tr><td colspan="3"><a href="/confignet#udpconfig">Puerto aleatorio</a> UDP de salida hacia otros puertos UDP remotos, mostrado en la página de Configuración de la Red, permitiendo respuestas.</td></tr>

<tr><td colspan="3">Puerto TCP de salida seleccionado de los puertos aleatorios altos hacia puertos TCP remotos.</td></tr>

<tr><td colspan="3">Puerto de entrada UDP hacia el <a href="/confignet#udpconfig">puerto</a> mostrado en la página de Configuración de la Red desde localizaciones aleatorias (opcional, pero recomendado).</td></tr>

<tr><td colspan="3">Conexiones al puerto TCP de entrada hacia el <a href="/confignet#externaltcp">puerto</a> mostrado en la página de Configuración de la Red, desde localizaciones aleatorias (opcional, pero recomendado). <a href="/confignet#tcpconfig">Inbound TCP</a> puede estar desactivado en la página de Configuración de la REd.</td></tr>

<tr><td colspan="3">UDP de salida en el puerto 123, permitiendo respuestas: este puerto es necesario para la sincronización interna de la hora de I2P. (vía SNTP - haciendo peticiones a un servidor SNTP aleatorio en <code>pool.ntp.org</code> u otro servidor que especifiques).</td></tr>

</table>

</div>

<div id="troubleshoot">
<h2>Resolución de problemas</h2>

<ul>

<p>Si estás teniendo problemas ejecutando I2P, la siguiente información puede ayudarte a identificar y resolver dichos problemas:</p>

<li><b>¡Ten paciencia!</b><br>
I2P puede ser lento hasta que se integra en la red la primera vez que lo ejecutas, mientras que se inicializa en la red y aprende de otros pares. Cuanto más tiempo esté ejecutado I2P, funcionará mejor, por lo que intenta mantener encendido tu rúter tanto como puedas, ¡24/7 si es posible! Si, tras 30 minutos, tu contador de <i>Activos: [conectados/recientes]</i> es menor de 5, hay varias cosas que puedes hacer para comprobar los problemas:</li>

<li><b>Comprueba tu configuración y el ancho de banda asignado</b><br>
I2P funciona mejor cuando puedes indicar de forma precisa la velocidad de la conexión de tu red en la <a href="/config">sección de configuración del ancho de banda</a>. Por defecto I2P está configurado con valores bastante conservadores que no se ajustan a todas las necesidades, por lo que , por favor, tómate tu tiempo en revisar estas configuraciones y ajústalas donde sea necesario. Cuanto más ancho de banda asignes, <i>en concreto</i>, cuanto más ancho de banda de subida, más te beneficiarás de la red.</li>

<li><b>Cortafuegos, Modems &amp; Rúters</b><br>
Donde sea posible, asegúrate de que I2P/Java tiene acceso bidireccional permitido a los puertos desde internet configurando el firewall de tu módem/rúter/pc. Si estás tras un cortafuegos restrictivo pero aún así tienes acceso no restringido de salida, I2P puede seguir funcionando; puedes desactivar el acceso de entrada y confiar en la <a href="http://i2p-projekt.i2p/udp.html" target="_blank" rel="noreferrer">Detección de dirección IP SSU</a> (<a href="https://wikipedia.org/wiki/Hole_punching" target="_blank" rel="noreferrer">firewall hole punching</a>) para que te conecte a la red, aunque el estado de red en el panel lateral indicará "Red: Bloqueada por cortafuegos". Para un rendimiento óptimo, donde sea posible, asegúrate de que los <a href="/confignet#udpconfig">puertos externos</a> de I2P son accesibles desde Internet (mira más abajo para más información).</li>

<li><b>Comprueba tu configuración Proxy</b><br>
Si no puedes acceder a ninguna eepsite (ni siquiera a <a href="http://i2p-projekt.i2p/" target="_blank" rel="noreferrer">i2p-projekt.i2p</a>), asegúrate que el proxy de tu navegador está configurado para acceder al tráfico http (<i>no</i> https, <i>no</i> socks) a través de <code>127.0.0.1 puerto 4444</code>. Si necesitas ayuda, hay <a href="https://geti2p.net/en/about/browser-config" target="_blank" rel="noreferrer">una guía</a> para configurar tu navegador y usarlo con I2P.</li>

<li><b>Comprueba los registros</b><br>
<a href="/logs">Los registros</a> puede ayudar a resolver los problemas. Quizás quieras pegar fragmentos de los registros en un <a href="http://i2pforum.i2p/" target="_blank" rel="noreferrer">foro</a> para obtener ayuda, o quizás <a href="http://zerobin.i2p/" target="_blank" rel="noreferrer">pegarlo</a> y enlazarlo en el IRC para obtener ayuda.</li>

<li><b>Asegúrate que tienes Java actualizado</b><br>
Asegúrate de que Java está actualizado [se necesita la versión 1.7 o mayor]. Comprueba la versión de tu JRE (<a href="https://wikipedia.org/wiki/JRE" target="_blank" rel="noreferrer">Java Runtime Environment</a>) en la parte de arriba de la <a href="/logs">página de los registros</a>. Date cuenta que el soporte de Java 10 está en estado beta, por lo que puedes tener problemas menores si usas Java 10 (por favor, reporta cualquier error que encuentres).</li>

<li><b>Problemas al ejecutarse en hardware antiguo</b><br>
[Linux/Unix/Solaris] Si no puedes iniciar el rúter con <code>i2p/i2prouter start</code>, prueba con el script <code>runplain.sh</code> que está en el mismo directorio. No se necesita (y no se recomienda) usar permisos de administrador para ejecutar I2P. Si necesitas compilar la <a href="http://i2p-projekt.i2p/jbigi.html" target="_blank" rel="noreferrer">librería jBigi</a> (lo cual es necesario en muy pocas ocasiones), consulta la documentación, visita los foros, o haz una visita a nuestro <a href="irc://127.0.0.1:6668/i2p-dev">canal IRC para desarrolladores</a>.</li>

<li><b>Activa Universal Plug and Play (UPnP)</b><br>
Tu módem o rúter pude que soporten <a href="https://wikipedia.org/wiki/Universal_Plug_and_Play" target="_blank" rel="noreferrer">Universal Plug &amp; Play</a> (UPnP), lo que permite el reenvío de puertos. Asegúrate de que el soporte UPnP para I2P está activado en la <a href="/confignet">página de configuración</a>, e intenta activar UPnP en tu módem/rúter y probáblemente también en tu computadora. Ahora intenta reiniciar el <a href="/">rúter I2P</a>. Si tiene éxito, I2P mostrará "Network: OK" en la barra lateral una vez que I2P complete los tests de conectividad.</li>

<li><b>Reenvío de puertos</b><br>
Abre los <a href="/confignet#udpconfig">puertos para I2P</a> en tu módem, rúter y/o cortafuegos para tener mejor conectividad (idealmente TCP y UDP). Más información sobre el reenvío de puertos puede encontrarse en <a href="http://portforward.com/" target="_blank" rel="noreferrer">portforward.com</a>, además de en nuestros foros y en los canales de IRC listados más abajo. Date cuenta que I2P no soporta el conectarse a Internet vía http o con un proxy socks [¡los parches son bienvenidos!], aunque puedes conectarte a proxies vía I2P una vez conectado a la red.</li>

<li><b>Obteniendo soporte online</b><br>
También puedes querer revisar la información en la <a href="http://i2p-projekt.i2p/" target="_blank" rel="noreferrer">I2P web</a>, poner mensajes en el <a href="http://i2pforum.i2p/viewforum.php?f=8" target="_blank" rel="noreferrer">for de discusión de I2P</a> o pasarte por <a href="irc://127.0.0.1:6668/i2p">#i2p</a> o por <a href="irc://127.0.0.1:6668/i2p-chat">#i2p-chat</a> en la red IRC interna de I2P. Estos canales también están disponibles fuera de I2P a través de <a href="irc://irc.freenode.net/i2p">Freenode</a> o de la red IRC <a href="irc://irc.freenode.net/i2p">OFTC</a>.</li>
<li><b>Reportando Bugs</b><br>
Si deseas reportar un bug, por favor, crea un ticket en <a href="http://trac.i2p2.i2p/" target="_blank" rel="noreferrer">trac.i2p2.i2p</a>. Para los discusiones de desarrolladores, visita <a href="http://zzz.i2p/" target="_blank" rel="noreferrer">el foro de desarrolladores de zzz</a> o visítanos en el <a href="irc://127.0.0.1:6668/i2p-dev">canal de desarrolladores</a> en la red IRC de I2P.</li>

<p>La información adicional, incluida la documentación avanzada, está disponible en la <a href="https://geti2p.net/" target="_blank" rel="noreferrer">página web del proyecto</a>.</p>
</div>
<div id="legal">
<h2>Aspectos jurídicos</h2>
<p>El rúter I2P (router.jar) y el SDK (i2p.jar) son casi enteros de domino público, con unas pocas excepciones destacables:</p>

<ul>
<li>El código de ElGamal y de DSA, escrito por TheCrypto, están bajo la licencia BSD.</li>
<li>SHA256 y HMAC-SHA256, escritos po la <a href="https://www.bouncycastle.org/" target="_blank" rel="noreferrer">Legion of the Bouncycastle</a>, están bajo la licencia MIT</li>
<li>El código de AES, escrito por el <a href="http://www.cryptix.org/" target="_blank" rel="noreferrer">Equipo Cryptix</a>, está bajo la licencia Cryptix (MIT)</li>
<li><a href="http://support.ntp.org/bin/view/Support/JavaSntpClient" target="_blank" rel="noreferrer">cñodigo SNTP</a>, bajo licencia BSD, escrito por Adam Buckley</li>
<li>El resto es de dominio públicon, escrito por jrandom, mihi, hypercubus, oOo, ugha, duck, shendaras y otros .</li>
</ul>

<p>Sobre el rúter I2P hay una serie de aplicaciones cliente, cada una con sus propias licencias y dependencias:</p>

<ul>
<li><a href="http://i2p-projekt.i2p/i2ptunnel" target="_blank" rel="noreferrer">I2PTunnel</a> - es una aplicación con licencia GPL escrita por mihi y que permite tunelar tráfico TCP-IP normal sobre I2P (como el proxy http y el proxy irc), con un <a href="/i2ptunnelmgr">interfaz para un navegador.</a></li>
<li><a href="/webmail">Susimail</a> - es un cliente de correo para un navegador con licencia GPL escrito por susi23.</li>
<li>LA <a href="http://i2p-projekt.i2p/en/docs/naming#addressbook" target="_blank" rel="noreferrer">aplicación de libreta de direcciones</a>, escrita por Ragnarok ayuda a administrar tu archivo hosts.txt, con un <a href="/dns">interfaz</a> escrito por susi23.</li>
<li>El puente <a href="http://i2p-projekt.i2p/en/docs/api/samv3" target="_blank" rel="noreferrer">SAM</a> de human (dominio público), es un API que pueden utilizar otras aplicaciones cliente (como el <a href="http://i2pwiki.i2p/index.php?title=Tahoe-LAFS" target="_blank" rel="noreferrer">sistema de archivos en la nuve Tahoe-LAFS para I2P</a>)</li>
<li><a href="http://i2p-projekt.i2p/en/misc/jbigi" target="_blank" rel="noreferrer">jbigi</a> - es una librería optimizada para hacer una gran número de cálculos que utiliza la librería con licencia LGPL <a href="http://swox.com/gmp/" target="_blank" rel="noreferrer">GMP</a>, optimizada para varias arquitecturas de PC.</li>
<li>Los lanzadores para los usuarios de windows están creados con <a href="http://launch4j.sourceforge.net/" target="_blank" rel="noreferrer">Launch4J</a>, y el instalador está construido con <a href="http://www.izforge.com/izpack/" target="_blank" rel="noreferrer">IzPack</a>.</li>
<li>Para implementar I2P como un servicio usamos la versión comunitaria del <a href="https://wrapper.tanukisoftware.com/doc/english/product-overview.html" target="_blank" rel="noreferrer">Tanuki Service Wrapper</a> que está licenciado bajo la GPLv2. Para obtener información de cómo actualizar el wrapper, mirar en <a href="http://i2p-projekt.i2p/en/misc/manual-wrapper" target="_blank" rel="noreferrer">la documentación online</a>.</li>
<li>La <a href="/">consola del rúter I2P</a>, creada desde una instancia de un servidor <a href="http://jetty.mortbay.com/jetty/index.html" target="_blank" rel="noreferrer">web Jetty</a> nos permite desplegar aplicaciones web estándar JSP/Servlet en tu rúter.</li>
<li>Jetty hace uso de la implementación <a href="https://docs.oracle.com/javaee/7/api/javax/servlet/package-summary.html" target="_blank" rel="noreferrer">javax.servlet</a> de Apache
(javax.servlet.jar). Este producto incluye software desarrollado por la <a href="http://www.apache.org/" target="_blank" rel="noreferrer">Apache Software Foundation</a>.</li>
<li>El código fuente de I2P y de la mayoría de las aplicaciones incluidas puede encontrarse en nuestra <a href="http://i2p-projekt.i2p/download" target="_blank" rel="noreferrer">página de descargas</a> y puede verse online en <a href="https://github.com/i2p" target="_blank" rel="noreferrer">Github</a>.</li>
<li>La consola del rúter y los <a href="/configui">temas</a> de la aplicación, además de la experiencia general del usuario, son cortesía de dr|z3d, licenciados bajo la licencia <a href="https://www.gnu.org/licenses/agpl-3.0.en.html" target="_blank" rel="noreferrer">Affero GPLv3</a>. Los diseños propios de I2Psnark que vienen con I2P+, y el logo de itoopieç+ <i>no</i> están licenciados para ser reusados a no ser que se permita explícitamente, y actualmente son exlusivos de <a href="http://skank.i2p/static/i2p+.html" target="_blank" rel="noreferrer">I2P+</a> (el <a href="http://p.yusukekamiyamane.com/icons/search/fugue/" target="_blank" rel="noreferrer">conjunto de iconos Fugue</a> se usa bajo la licencia Creative Commons v3).</li>
</ul>

<p>Para más detalles sobre otras aplicaciones disponibles, y de sus licencias, mira las <a href="http://i2p-projekt.i2p/licenses" target="_blank" rel="noreferrer"> políticas de licencias</a>. Las demás licencias pueden encontrarse en el subdirectorio <code>licenses</code> de tu instalación de I2P.</p>

</div>
<div id="changelog">
<h2>Registro de cambios</h2>
 <jsp:useBean class="net.i2p.router.web.ContentHelper" id="contenthelper" scope="request" />
 <% java.io.File fpath = new java.io.File(net.i2p.I2PAppContext.getGlobalContext().getBaseDir(), "history.txt"); %>
 <jsp:setProperty name="contenthelper" property="page" value="<%=fpath.getAbsolutePath()%>" />
 <jsp:setProperty name="contenthelper" property="maxLines" value="768" />
 <jsp:setProperty name="contenthelper" property="startAtBeginning" value="true" />
 <jsp:getProperty name="contenthelper" property="textContent" />
<p id="fullhistory"><a href="/history.txt" target="_blank" rel="noreferrer">Ver todo el registro de cambios</a></p>
</div>

</div>
<%@include file="summaryajax.jsi" %>
<script nonce="<%=cspNonce%>" type="text/javascript">window.addEventListener("pageshow", progressx.hide());</script>
</body>
</html>