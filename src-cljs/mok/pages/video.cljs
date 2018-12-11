(ns ^:figwheel-always mok.pages.video
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :as utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                                default-error-handler ts->readable-time
                                maybe-upload-file
                                make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                                load-sellers! oss-res]]
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
   [reagent.crypt :as crypt]
   [secretary.core :as secretary :include-macros true]
   [ajax.core :refer [GET PUT DELETE POST]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]))

(defonce x-tutorials-store (atom []))

(defonce default-video {:x-images [] :title nil})

(defonce tutorial-store (atom {:dir (str (.getTime (js/Date.)))
                               :x-video [(atom default-video)]})) 

(defn make-temp-video-atom []
  (atom (assoc default-video :id (.getTime (js/Date.)))))

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
   :calory "热量消耗（kCal）"
   :dir "目录"
   })

(defn- validate-tutorial [{:keys [dir title tag cover reqdev duration advice audient warning calory x-video]}]
  )

;; {:calory "2.2", :dir "1544340238665", :x-video [#<Atom: {:x-images ["1544340238665/1544340238665/ether.mp4/2018-12-08-18:51_1544266280_948x334.png" "1544340238665/1544340238665/ether.mp4/2018-12-05-08:35_1543970155_286x310.png"], :title "1544340238665/ether.mp4", :uploading? true}> #<Atom: {:x-images ["1544340238665/1544340238665/trezor-pin.mp4/2018-12-08-18:51_1544266280_948x334.png" "1544340238665/1544340238665/trezor-pin.mp4/2018-12-05-08:35_1543970155_286x310.png"], :title "1544340238665/trezor-pin.mp4", :id 1544340737303}>], :cover "1544340238665/2018-12-08-18:51_1544266280_948x334.png", :duration "223", :advice "无", :title "test", :warning "无", :audience "所有", :tag "test"}
(defn- upload! []
  (let [tutorial @tutorial-store]
    (validate-tutorial tutorial)
    (t/info "adding" tutorial)))

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

(defn- video-uploader [video-store]
  (let [{:keys [title x-images uploading?]} @video-store]
    [:div.video-edit__upload-sec
     [:h3 {:on-click (fn [_]
                       (when (js/confirm "确认删除此视频？")
                         (swap! tutorial-store update :x-video #(remove (partial = video-store) %))))}
      (if (s/blank? title)
        "请上传视频"
        (str "视频：" title))
      (when uploading?
        [:img.video-edit__spinner {:src "/images/spinner4.svg"}])]
     (if (s/blank? title)
       [:input
        {:type :file
         :on-change (fn [e]
                      (let [file (first (array-seq (.. e -target -files)))
                            dir (:dir @tutorial-store)
                            ky (when file (str dir "/" (.-name file)))]
                        (when (and ky
                                   (not (some (partial = ky)
                                              (map (fn [m] (:title @m))
                                                   (:x-video @tutorial-store)))))
                          (if (s/ends-with? ky ".mp4")
                            (do
                              (swap! video-store assoc :uploading? true)
                              (utils/oss-upload ky file
                                                (fn [resp]
                                                  (swap! video-store assoc :title ky))
                                                (fn [] (swap! video-store dissoc :uploading?))))
                            (js/alert (str "不支持此文件类型：" (.-name file)))))))}]
       [:video {:width 640 :height 480 :controls true}
        [:source {:src (oss-res title)}]])
     (when (not (s/blank? title))
       [:div
        [:div.video-edit__input-label "视频分解图"]
        (when (seq x-images)
          [:div.video-edit__video-imges
           (doall
            (for [image x-images]
              [:img.video-edit__img
               {:key (str "ve.img." image)
                :src (oss-res image)
                :on-click #(when (js/confirm "确认删除？")
                             (swap! video-store (fn [xs] (remove (partial = image) xs))))}]))])
        [:input
         {:type :file
          :on-change (fn [e]
                       (let [file (first (array-seq (.. e -target -files)))
                             dir (:dir @tutorial-store)
                             ky (when file (str dir "/" title "/" (.-name file)))]
                         (when (and ky (not (some (partial = ky) x-images)))
                           (utils/oss-upload ky file (fn [resp]
                                                       (swap! video-store update :x-images conj ky))))))}]])]))

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
    [:div.video-edit__input.video-edit__input--oneline
     [:div.video-edit__input-label "需要器材？"]
     [:input {:type :checkbox :on-change #(swap! tutorial-store update :rqdev not)}]]
    [:div
     [:div.video-edit__input-label "封面图（1张）"]
     (if (s/blank? (:cover @tutorial-store))
       [:input
        {:type :file
         :on-change (fn [e]
                      (let [file (first (array-seq (.. e -target -files)))
                            dir (:dir @tutorial-store)
                            ky (str dir "/" (.-name file))]
                        (utils/oss-upload ky file (fn [resp]
                                                    (swap! tutorial-store assoc :cover ky)))))}]
       [:img.video-edit__img {:src (oss-res (:cover @tutorial-store))
                              :on-click #(when (js/confirm "确认删除？")
                                           (swap! tutorial-store dissoc :cover))}])]
    [:div.video-edit__video-upload
     [:div.video-edit__input-label "视频（按序上传）"]
     (doall
      (for [video-store (:x-video @tutorial-store)]
        ^{:key (str "ve.v.id." (:id @video-store))}
        [video-uploader video-store]))
     [:div.video-edit__video-upload-add
      [:a.btn-light {:href "javascript:;"
                     :on-click #(swap! tutorial-store update :x-video conj (make-temp-video-atom))}
       "添加一段视频"]]]
    [:a.btn-light {:href "javascript:;" :on-click #(upload!)} "添加本课程"]]])

(defn videos-page []
  (set-title! "管理")
  (load-tutorials!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "视频"]]
     [tutorial-list-panel]
     [tutorial-edit-panel]]))
 
