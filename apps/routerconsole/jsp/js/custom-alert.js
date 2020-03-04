/**
 * customalerts.js
 * Author: Philippe Assis
 * Doc and repo: https://github.com/PhilippeAssis/custom-alert
 *
 * Alert e confirm personalizados.
 * FF, Chromer, IE(>=9)*
 *
 *                              ATENÇÂO
 * window.customalert e window.customconfirm devem permanecer com esses nomes,
 * a não ser que você saiba o que esta fazendo.
 */



function customAlert(inGlobalVar) {

    var createDom = function(type) {
        this.el = document.createElement(type);

        this.attr = function(attr) {
            var $this = this;

            for (var key in attr) {
                $this.el.setAttribute(key, attr[key]);
            }

            return $this;
        }

        this.parent = function(parent, wrap) {
            wrap = (wrap) ? document.querySelector(wrap) : document;
            parent = wrap.querySelector(parent)
            parent.appendChild(this.el);
            return this;
        }


        this.html = function(html) {
            this.el.innerHTML = html
            return this;
        }

        return this;
    }

    function mergeObjects(obj1, obj2) {
        for (var key in obj2) {
            obj1[key] = obj2[key];
        }

        return obj1;
    }

    function Alert() {
        var AlertDefaultOptions = {
            'button': 'OK',
            'title': 'Alert'
        };

        this.render = function(dialog, options) {
            if (options) {
                this.options = mergeObjects(AlertDefaultOptions, options);
            }
            else {
                this.options = AlertDefaultOptions;
            }

            var alertBox = document.querySelector("#customalert");
            alertBox.querySelector(".header").innerHTML = this.options.title;
            alertBox.querySelector(".body").innerHTML = dialog;
            alertBox.querySelector(".button-done").innerHTML = this.options.button;
            document.querySelector("html").style.overflow = "hidden";
            document.querySelector("#customalert-overlay").style.display = "block";
            alertBox.style.display = "block";
        };

        this.done = function() {
            document.querySelector("#customalert").style.display = null;
            document.querySelector("#customalert-overlay").style.display = null;
            document.querySelector("html").style.overflow = "auto";

            if (typeof this.callback == 'function') {
                this.callback.call()
            }
        }
    }

    function Confirm() {
        var confirmDefaultOptions = {
            "done": {
                "text": "Ok",
                "bold": false,
                "default": true
            },
            "cancel": {
                "text": "Cancel",
                "bold": false,
                "default": false
            },
            'title': 'Confirm'
        };

        var getText = function(options, obj) {
            if (options[obj].bold) {
                return "<strong>" + options[obj].text + "</strong>"
            }

            return options[obj].text
        }

        this.callback = function(data) {};

        this.render = function(dialog, options) {
            this.options = confirmDefaultOptions;

            if (options) {
                if (options.done && typeof options.done == "string") {
                    options.done = {
                        "text": options.done
                    }
                }

                if (options.cancel && typeof options.cancel == "string") {
                    options.cancel = {
                        "text": options.cancel
                    }
                }

                if (options.cancel.default == true) {
                    options.done.default = false;
                }
                else {
                    options.done.default = true;
                }

                console.log(confirmDefaultOptions)
                if (options.cancel) {
                    this.options.cancel = mergeObjects(confirmDefaultOptions.cancel, options.cancel)
                }
                if (options.done) {
                    this.options.done = mergeObjects(confirmDefaultOptions.done, options.done)
                }
                if (options.title) {
                    this.options.title = options.title
                } 
            }

            var confirmBox = document.querySelector("#customconfirm");
            confirmBox.querySelector(".header").innerHTML = this.options.title;
            confirmBox.querySelector(".body").innerHTML = dialog;
            confirmBox.querySelector(".button-cancel").innerHTML = getText(this.options, "cancel");
            confirmBox.querySelector(".button-done").innerHTML = getText(this.options, "done");
            document.querySelector("html").style.overflow = "hidden";
            document.querySelector("#customconfirm-overlay").style.display = "block";
            confirmBox.style.display = "block";
        };

        this.done = function() {
            this.end();

            if (this.callbackSuccess) {
                return this.callbackSuccess();
            }

            this.callback(true);
        }

        this.cancel = function() {
            this.end();

            if (this.callbackCancel) {
                return this.callbackCancel();
            }

            this.callback(false);
        }

        this.end = function() {
            document.querySelector("#customconfirm").style.display = "none";
            document.querySelector("#customconfirm-overlay").style.display = "none";
            document.querySelector("html").style.overflow = "auto";
        }
    }

    var cAlert, cConfirm;

    if (document.getElementById("customalert") == null) {
        createDom('div').attr({
            "id": "customalert-overlay",
            "class": "customalert-overlay"
        }).parent("body")

        createDom('div').attr({
            "id": "customalert",
            "class": "customalert customalert-alert"
        }).parent("body")

        createDom("div").attr({
            "class": "header"
        }).parent("#customalert");

        createDom("div").attr({
            "class": "body"
        }).parent("#customalert");

        createDom("div").attr({
            "class": "footer"
        }).parent("#customalert");

        createDom("button").attr({
            "class": "btn btn-primary custom-alert button-done",
            "onclick": "window.customalert.done()"
        }).parent(".footer", "#customalert");


        window.addEventListener('keydown', function(e) {
        	var customConfirm = document.getElementById("customconfirm");
        	var customAlert = document.getElementById("customalert");
        	
        	if( (customAlert == null && customConfirm == null) 
        			|| (customConfirm != null && customConfirm.style.display != "block")
        			|| (customAlert != null && customAlert.style.display != "block") ){
        		return;
        	}
        	
        	e.preventDefault();
        	e.stopPropagation();
        
            var keynum = e.keyCode ? e.keyCode : e.which;

            if (keynum == 13) {
                if (customConfirm.style.display == "block") {
                    if (window.customconfirm.options.cancel.default) {
                        window.customconfirm.cancel();
                    }
                    else {
                        window.customconfirm.done();
                    }
                } else if (customAlert.style.display == "block") {
                    window.customalert.done();
                }
            }
            else if (keynum == 27 && customConfirm.style.display == "block"){
                window.customconfirm.cancel();
            }

        }, false);

        cAlert = window.Alert = function(dialog, options, callback) {
            window.customalert = new Alert();
            if (typeof options == 'function') {
                window.customalert.callback = options;
                options = null
            }
            else {
                window.customalert.callback = callback || null;
            }

            window.customalert.render(dialog, options);
        };
    }

    if (document.getElementById("customconfirm") == null) {
        createDom('div').attr({
            "id": "customconfirm-overlay",
            "class": "customalert-overlay"
        }).parent("body")

        createDom('div').attr({
            "id": "customconfirm",
            "class": "customalert customalert-confirm"
        }).parent("body")

        createDom('div').attr({
            "class": "header",
        }).parent("#customconfirm")

        createDom('div').attr({
            "class": "body",
        }).parent("#customconfirm")

        createDom('div').attr({
            "class": "footer",
        }).parent("#customconfirm")

        createDom('button').attr({
            "class": "btn btn-danger button-cancel",
            "onclick": "window.customconfirm.cancel()"
        }).parent(".footer", "#customconfirm")

        createDom('button').attr({
            "class": "btn btn-success custom-alert button-done",
            "onclick": "window.customconfirm.done()"
        }).parent(".footer", "#customconfirm")


        cConfirm = window.Confirm = function(dialog, callback, options) {
            window.customconfirm = new Confirm();
            if (typeof callback == 'object') {
                window.customconfirm.callbackSuccess = callback.success;
                window.customconfirm.callbackCancel = callback.cancel;
            }
            else {
                window.customconfirm.callback = callback;
            }

            window.customconfirm.render(dialog, options);
        };

    }


    if (inGlobalVar === false) {
        return {
            "alert": cAlert,
            "confirm": cConfirm
        }
    }
    else {
        window.alert = cAlert
        window.confirm = cConfirm
    }
}
