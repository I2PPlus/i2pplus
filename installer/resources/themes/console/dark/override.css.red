/* Purple override.css for console 'dark' theme */
/* Author: dr|z3d */

body, html {
    min-height: 100%;
}

html {
    filter: hue-rotate(-110deg);
}

body {
    background: repeating-linear-gradient(to right, rgba(0,0,0,0.8) 1px, rgba(0,0,0,0.8) 2px, rgba(32,0,32,0.6) 3px) #000;
}

img, #sb_localtunnels img, .routersummary *::before, #routerlogs li, div.app img {
    filter: hue-rotate(110deg) !important;
}

h1, h2, h3, h4, h4 span, body, td, p, #news p, #newsStatus, #console p, #console li, th, .tab2, #sb_peers, #sb_peers a {
    color: rgba(220,228,220,0.95) !important;
}

h1, h4.app, h4.app2 {
    background-blend-mode: luminosity, normal, exclusion, normal !important;
}

.main#console li b, code, .routersummary h4 {
    color: rgba(100,228,160,0.95) !important;
}

.newsAuthor {
    mix-blend-mode: luminosity;
}

.routersummary div[style] img {
    filter: hue-rotate(0) saturate(0.5) !important;
    mix-blend-mode: luminosity !important;
}

.routersummary div[style] img:hover {
    filter: hue-rotate(0) saturate(1.5) !important;
    mix-blend-mode: normal !important;
}

.infohelp, .infowarn, .bw_in, .bw_out, .bw_share {
    background-blend-mode: luminosity;
}

.net_bwrate::before {
    filter: sepia(1) saturate(0) !important;
}

#routerlogs li:hover, #criticallogs li:hover {
    background: rgba(64,0,64,0.3);
}