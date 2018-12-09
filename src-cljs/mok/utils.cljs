(ns mok.utils
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [mok.states :refer [me companylist seller-list-store get-oss
                       jianqing-oss-base]]
   
   [clojure.string :as s]
   [ajax.core :refer [GET PUT DELETE]]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom dom-node]]
   [reagent.session :as session]
   [cljs-pikaday.reagent :as pikaday]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]
   )
  (:import [goog History]))


(defn console-print [args]
  (.apply (.-log js/console) js/console (into-array args))
  args)

(defonce session-expired? (atom false))

;;(set! *print-fn* (fn [& args] (-> args console-print)))
(defonce ^:dynamic *toast-timeout* 1000)
(defonce ^:dynamic *toast-clear* nil)

(defn clear-toast []
  (js/toastr.clear))


(defn make-toast
  ([title]
   (make-toast :info "" title))
  ([level title]
   (make-toast level "" title))
  ([level msg title]
   (when *toast-clear*
     (clear-toast))
   ((case level
      :info js/toastr.info
      :warn js/toastr.warning
      :error js/toastr.error)
    msg title (clj->js {:timeOut *toast-timeout*}))))

(defn error-toast
  "Make an error toast and return true."
  [msg]
  (make-toast :error msg)
  true)

(defonce dt-format "yyyy-MM-dd")

;; Pikaday constructor args
;; https://github.com/dbushell/Pikaday
(defonce pikaday-construct
  {:format "YYYY-MM-DD"
   :i18n {:previousMonth "上一月"
          :nextMonth     "下一月"
          :months         ["一月" "二月" "三月" "四月" "五月" "六月" "七月" "八月" "九月" "十月" "十一月" "十二月"],
          :weekdaysShort  ["日" "一" "二" "三" "四" "五" "六"]
          :weekdays ["日" "一" "二" "三" "四" "五" "六"]}})


(def date-formatter (formatter dt-format))
(def datetime-formatter (formatter "yyyy-MM-dd HH:mm:ss"))


(defn loading-spinner
  [show?]
  (fn [show?]
    [:div.loadingDiv
     {:style {:display (when show? :block)}}
     [:div.spinner
      [:div.spinner-container.container1
       [:div.circle1] [:div.circle2] [:div.circle3] [:div.circle4]]
      [:div.spinner-container.container2
       [:div.circle1] [:div.circle2] [:div.circle3] [:div.circle4]]
      [:div.spinner-container.container3
       [:div.circle1] [:div.circle2] [:div.circle3] [:div.circle4]]]]))


(defonce color-palette
  [["#000","#444","#666","#999","#ccc","#eee","#f3f3f3","#fff"],
   ["#f00","#f90","#ff0","#0f0","#0ff","#00f","#90f","#f0f"],
   ["#f4cccc","#fce5cd","#fff2cc","#d9ead3","#d0e0e3","#cfe2f3","#d9d2e9","#ead1dc"],
   ["#ea9999","#f9cb9c","#ffe599","#b6d7a8","#a2c4c9","#9fc5e8","#b4a7d6","#d5a6bd"],
   ["#e06666","#f6b26b","#ffd966","#93c47d","#76a5af","#6fa8dc","#8e7cc3","#c27ba0"],
   ["#c00","#e69138","#f1c232","#6aa84f","#45818e","#3d85c6","#674ea7","#a64d79"],
   ["#900","#b45f06","#bf9000","#38761d","#134f5c","#0b5394","#351c75","#741b47"],
   ["#600","#783f04","#7f6000","#274e13","#0c343d","#073763","#20124d","#4c1130"]])

;; Sample usage
;; (def pikacolor
;;   (with-meta -pikacolor
;;     {:component-did-mount
;;      (fn [this] (.spectrum (js/$ (r/dom-node this))
;;                            (clj->js (make-spectrum-conf {:color "333" :on-change #(swap! cat-state assoc :bgcolor (subs (.toHexString %) 1))}))))}))

(defn make-spectrum-conf
  "More options, see
  http://bgrins.github.io/spectrum/ "
  [{:keys [color on-change]}]
  {:preferredFormat "hex"
   :change on-change
   :hideAfterPaletteSelect true
   :showPaletteOnly true
   :togglePaletteOnly true
   :togglePaletteMoreText "more"
   :togglePaletteLessText "less"
   :color (str (when-not (re-seq #"^#" color) "#") color)
   :palette color-palette})


(defn admin?
  []
  (= 65535 (:rightcode @me)))

(defn hawks-admin?
  []
  (#{65535} (:rightcode @me)))

(defn default-error-handler
  ([url {:keys [status status-text]}]
   (t/error  "something bad happened " "when requesting" (or url) status " " status-text)
   (make-toast :error (str "请求失败：" status " " status-text)))
  ([resp]
   (default-error-handler nil resp)))


(defn make-resp-handler
  "Create a json-response handler which show a `msg-success` toast upon success, or `msg-fail` upon failure"
  [& [{:keys [msg-success msg-fail callback-success callback-fail]}]]
  (fn [{:keys [code msg data] :as resp}]
    (case code
      0 (do
          (when msg-success
            (make-toast msg-success)) 
          (when callback-success
            (callback-success resp)))
      2 (do
          (make-toast :error "登录状态过期，请重新登录！")
          (reset! session-expired? true)
          (.replace js/location  "/"))
      (do
        (make-toast :error (str code " : " msg) (or msg-fail "请求失败！"))
        (when callback-fail
          (callback-fail resp))))))


(defn get-window-height
  []
  (or (.-innerHeight js/window)
      (.-clientHeight (.-documentElement js/document))))

(defn get-window-width
  []
  (or (.-innerWidth js/window)
      (.-clientWidth (.-documentElement js/document))))

(defn element-in-viewport?
  [el]
  (let [rect (.getBoundingClientRect el)
        t (.-top rect)
        l (.-left rect)
        b (.-bottom rect)
        r (.-right rect)]
    (and (>= t 0)
         (>= l 0)
         (<= b (get-window-height))
         (<= r (get-window-width)))))

(defn wrap-session-check
  "Wrap response handler with a session check, returns to login page
  if session is expired."
  [func]
  (fn [resp]
    (if (= 2 (:code resp))
      (do
        (set! (.-href js/location) "/logout")
        (session/clear!))
      (func resp))))

(defn cid->name
  "Convert company id to display name"
  [cid]
  (when @companylist
    (:name (first (filter #(= cid (:id %)) @companylist)))))

(defn set-title!
  ([base title]
   (set! (.-title js/document) (str base " " title)))
  ([title]
   (set-title! "" title)))

;; (defn get-browser-info
;;   []
;;   (.-sayswho js/navigator))

;; (defn browser-compatible?
;;   []
;;   (when-let [ifo (get-browser-info)]
;;     (let [[browser ver] (s/split ifo #"\s+")]
;;       (and (= (.toLowerCase browser) "chrome")
;;            (> (js/parseInt ver) 40)))))



(defn ip->int
  "Convert ipv4 string to int"
  [text]
  (reduce
   (fn [n [i a]] (+ n (bit-shift-left a (- 24 (* i 8)))))
   0
   (map-indexed (fn [i a] [i (js/parseInt a)]) (s/split text #"\."))))

(defn int->ip
  "Convert int to ipv4 string"
  [ip]
  (s/join "." (map #(bit-and (bit-shift-right ip (- 24 (* % 8))) 0xff) (range 4))))

(defn ts->readable-time
  [ts]
  (unparse datetime-formatter (to-default-time-zone (from-long ts))))

(defn spacer
  [& [width]]
  [:span {:style {:width (or width "12px") :display :inline-block}}])

(def ^:private rights-map
  {:company 2r1
   :wx-device 2r10
   :feedback 2r100
   :broadcast 2r1000
   :usermanage 2r10000})

(def rightcode-bit-map
  {2 {:key :feedback :title "用户反馈"}
   3 {:key :broadcast :title "推送消息"}
   4 {:key :usermanage :title "用户管理"}
   6 {:key :mblog :title "社区活动"}})

(def map-rightcode
  {:feedback {:key :feedback :title "用户反馈" :n 2}
   :broadcast {:key :broadcast :title "推送消息" :n 3}
   :usermanage {:key :usermanage :title "用户管理" :n 4}
   :mblog {:key :mblog :title "社区活动" :n 6}})

(defn user-has-right? [right-key]
  (when-let [n  (get-in map-rightcode [right-key :n])]
    (bit-test (:rightcode @me) n)))

(defn format-date [d]
  (when d
    (if (number? d)
      (format-date (js/Date. d))
      (.format (js/moment d) "YYYY-MM-DD"))))

(defn- upload-static-file [{:keys [file callback-success]}]
  (when file
    (PUT (str "/banner/image/" (.getTime (js/Date.)))
         {:body (doto (js/FormData.)
                  (.append "imageElement" file))
          :response-format :json
          :keywords? true
          :timeout 60000
          :handler (make-resp-handler
                    {:msg-success "保存成功！" :msg-fail "请求失败！"
                     :callback-success callback-success})
          :error-handler default-error-handler})))

(defn status-text [status]
  ({"0" "禁用"
    "1" "正常"
    "2" "禁言"}
   (str status)))

(defn set-hash! [loc]
  (set! (.-hash js/window.location) loc))

(defn redirect! [loc]
  (set! (.-location js/window) loc))

(defn open! [loc]
  (.open js/window loc))


(defn load-sellers! []
  (GET "/seller"
       {:response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! seller-list-store (:data %))})
        :error-handler default-error-handler}))

(defn upload-file
  "Upload static file shared by broadcasts and product images"
  [{:keys [image callback-success]}]
  (PUT (str "/broadcast/image/" (.getTime (js/Date.)))
       {:body (doto (js/FormData.)
                (.append "imageElement" image))
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-success "保存成功！" :msg-fail "请求失败！"
                   :callback-success callback-success})
        :error-handler default-error-handler}))

(defn maybe-upload-file [file cb-success]
  (cond
    (not (pos? (.-size file))) (make-toast :error "不能上传0字节大小文件。")
    (> (.-size file) (* 4 1024 1024)) (make-toast :error "文件大小不能超过4MB。")
    :else (upload-file {:image file :callback-success cb-success})))

(defn oss-upload [key file cb]
  (let [client (get-oss)
        p (.put client key file)]
    (when cb
      (.then p cb))))

(defn oss-res [ky]
  (str jianqing-oss-base ky))
