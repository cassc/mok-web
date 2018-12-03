(ns ^:figwheel-always mok.pages.video
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :as utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                                default-error-handler ts->readable-time
                                maybe-upload-file
                                make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                                load-sellers!]]
   [mok.states :refer [seller-list-store]]
   
   [alandipert.storage-atom       :refer [local-storage]]
   [cljs.core.async :as async
    :refer [>! <! put! chan alts!]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [cljs-pikaday.reagent :as pikaday]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [ajax.core :refer [GET PUT DELETE POST]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]))

(defonce x-tutorials-store (atom []))
(defonce tutorial-store (atom {}))

(defn load-tutorials! []
  )

(defn tutorial-list-panel []
  [:div "list of tutorials"])

(def key-title
  {:title "标题"
   :tag "标签以逗号分开"
   :cover "封面图"
   :rqdev "是否要器械"
   :duration "课程总长度"
   :advice "建议"
   :audience "适合人群"
   :warning "注意事项"
   :calory "热量消耗"
   :dir "目录"
   })

(defn plain-input [key]
  [:div.video-edit__input
   [:div.video-edit__input-label (key-title key)]
   [:input {:type :text
            :placeholder (key-title key)
            :value (key @tutorial-store)
            :on-change #(swap! tutorial-store assoc key (-> % .-target .-value))}]])

(defn num-input [key]
  [:div.video-edit__input
   [:div.video-edit__input-label (key-title key)]
   [:input {:type :number
            :placeholder (key-title key)
            :value (key @tutorial-store)
            :on-change #(swap! tutorial-store assoc key (-> % .-target .-value))}]])

(defn tutorial-edit-panel []
  [:div.video
   [:h3 "添加课程"]
   [:div.video-edit
    [plain-input :dir]
    [plain-input :title]
    [plain-input :tag]
    [plain-input :calory]
    [plain-input :warning]
    [plain-input :advice]
    [plain-input :audience]
    [num-input :duration]
    [:input {:type :checkbox :on-change #(swap! tutorial-store update :rqdev not)}]
    [:div
     [:img {:src "" :alt "cover"}]
     [:input
      {:type :file
       :on-change (fn [e]
                    (let [file (first (array-seq (.. e -target -files)))
                          dir (:dir @tutorial-store)]
                      (utils/oss-upload (str dir (.-name file)) file)))}]]
    [:div
     [:input {:type :file}] ;; video source
     [:div
      [:img {:src "" :alt "video-image"}]
      [:input {:type :file}]]]]])

(defn videos-page []
  (set-title! "管理")
  (load-tutorials!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "视频"]]
     [tutorial-list-panel]
     [tutorial-edit-panel]]))
 
