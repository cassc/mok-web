(ns ^:figwheel-always mok.pages.acts
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [alandipert.storage-atom       :refer [local-storage]]
   [cljs.core.async :as async
    :refer [>! <! put! chan alts!]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                      default-error-handler ts->readable-time
                      pikaday-construct format-date
                      error-toast make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                      spacer upload-static-file]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [cljs-pikaday.reagent :as pikaday]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [ajax.core :refer [GET POST PUT DELETE]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]))

(defonce loading? (atom nil))
(defonce act-list-store (atom []))
(defonce act-state (atom nil))
(defonce start-date (atom (js/Date.)))
(defonce end-date (atom (js/Date.)))

(def m-status {"online" "推广中"
               "offline" "活动下线"
               "pending" "规划中"})

(defn get-act-list []
  (reset! loading? true)
  (GET "/act"
       {:handler (make-resp-handler
                  {:callback-success
                   #(reset! act-list-store (:data %))})
        :error-handler (partial default-error-handler "/act")
        :response-format :json
        :keywords? true
        :finally #(reset! loading? nil)}))

(defn- upsert-act []
  (let [params (assoc @act-state :ts_start (.getTime @start-date) :ts_end (.getTime @end-date))]
    (PUT "/act"
         {:params params
          :format :json
          :response-format :json
          :keywords? true
          :timeout 60000
          :handler (make-resp-handler
                    {:msg-success "保存成功！" :msg-fail "请求失败！"
                     :callback-success #(do
                                          (get-act-list)
                                          (reset! act-state nil))})
          :error-handler default-error-handler})))

(defn li-style
  [width & [more-style]]
  {:style (merge {:width width} more-style)})

(defonce clear-div [:div {:style {:clear :both}}])


(defn- start-pickday [init-ts]
  (when init-ts
    (reset! start-date (js/Date. init-ts)))
  (fn []
    [pikaday/date-selector
     {:date-atom start-date
      :input-attrs {:style {:width "80px"}}
      :pikaday-attrs pikaday-construct}]))
(defn- end-pickday [init-ts]
  (when init-ts
    (reset! end-date (js/Date. init-ts)))
  (fn []
    [pikaday/date-selector
     {:date-atom end-date
      :input-attrs {:style {:width "80px"}}
      :pikaday-attrs pikaday-construct}]))

(defn act-item [bn]
  (fn [{:keys [id title slogan pica picb howtojoin ts_start ts_end reward description status nusers nposts position] :as bn}]
    [:div.fd-div
     [:ul.fd-line 
      [:li.fd-li (li-style "5%") id]
      [:li.fd-li (li-style "5%") title]
      [:li.fd-li (li-style "5%") slogan]
      [:li.fd-li (li-style "15%")
       [:img {:style {:width "50px" :height "50px"} :src pica}]
       [:span " "]
       [:img {:style {:width "60px" :height "80px"} :src picb}]]
      [:li.fd-li (li-style "10%") howtojoin]
      [:li.fd-li (li-style "10%") (str (or nposts 0) "/" (or nusers 0))]
      [:li.fd-li (li-style "10%") (format-date ts_start)]
      [:li.fd-li (li-style "10%") (format-date ts_end)]
      [:li.fd-li (li-style "5%") reward]
      [:li.fd-li (li-style "10%") description]
      [:li.fd-li (li-style "5%") (m-status status)]
      [:li.fd-li (li-style "5%") position]
      [:li.fd-li (li-style "5%")
       [:a {:href "javascript:;"
            :on-click #(reset! act-state bn)}
        "编辑"]]]]))

(defn act-list-ul []
  (fn []
    [:div {:style {:margin-top "10px"}}
     [:div.fd-divs
      [:ul.fd-lines
       [:li.fd-lis (li-style "5%") "ID"]
       [:li.fd-lis (li-style "5%") "主题"]
       [:li.fd-lis (li-style "5%") "口号"]
       [:li.fd-lis (li-style "15%") "图片"]
       [:li.fd-lis (li-style "10%") "参与方式"]
       [:li.fd-lis (li-style "10%") "打卡数/参与人数"]
       [:li.fd-lis (li-style "10%") "发起时间"]
       [:li.fd-lis (li-style "10%") "结束时间"]
       [:li.fd-lis (li-style "5%") "奖励"]
       [:li.fd-lis (li-style "10%") "活动说明"]
       [:li.fd-lis (li-style "5%") "状态"]
       [:li.fd-lis (li-style "5%") "位置"]
       [:li.fd-lis (li-style "5%") "操作"]]
      clear-div]
     (doall
      (for [bn @act-list-store]
        ^{:key (:id bn)}
        [act-item bn]))]))

(defn- act-edit-dialog
  []
  (fn []
    (let [{:keys [id title slogan pica picb howtojoin start end reward description status ts_start ts_end position]} @act-state]
      [:div#act-edit-dialog-parent.alertViewBox {:style {:display :block :text-align :left}}
       [:div#act-edit-dialog.alertViewBoxContent
       [:h4 (if id "编辑" "添加") "活动"]
       [:div.ct.edit
        [:div "活动主题"
         [:input
          {:type :text
           :value title
           :on-change #(swap! act-state assoc :title (-> % .-target .-value))}]]
        [:div "活动口号"
         [:input
          {:type :text
           :value slogan
           :on-change #(swap! act-state assoc :slogan (-> % .-target .-value))}]]
         [:div "活动时间"
         [start-pickday ts_start]
         [:span "至"]
         [end-pickday ts_end]]   
                 [:div "活动状态"
         [:select {:name "tag"
                   :value (or status "pending")
                   :on-change #(let [idx (.. % -target -selectedIndex)]
                                 (swap! act-state assoc :status (-> (aget (.-target %) idx) .-value)))}
          [:option {:value "pending"} "规划中"]
          [:option {:value "online"} "推广中"]
          [:option {:value "offline"} "活动下线"]]]
        [:div "位置"
         [:input
          {:type :number
           :value position
           :on-change #(swap! act-state assoc :position (-> % .-target .-value))}]]
        [:div
         "活动封面"
         [:p
         "活动首页封面"
         [:span.red  "(350 X 120)"]
          [:img {:style {:width "50px" :height "50px"} :src pica}]
          [:input
           {:type :file
            :on-change (fn [e]
                         (let [file (first (array-seq (.. e -target -files)))]
                           (upload-static-file
                            {:file file
                             :callback-success
                             #(when-let [src (:data %)]
                                (swap! act-state assoc :pica src))})))}]]
         [:p 
         "活动主页封面"
         [:span.red "(375 X 150)"]
          [:img {:style {:width "60px" :height "80px"} :src picb}]
          [:input
           {:type :file
            :on-change (fn [e]
                         (let [file (first (array-seq (.. e -target -files)))]
                           (upload-static-file
                            {:file file
                             :callback-success
                             #(when-let [src (:data %)]
                                (swap! act-state assoc :picb src))})))}]]]
        [:div "参与方式"
         [:textarea.textareaHeight
          {:value howtojoin
           :on-change #(swap! act-state assoc :howtojoin (-> % .-target .-value))}]]
        [:div "活动奖励"
         [:textarea.textareaHeight
          {:value reward
           :on-change #(swap! act-state assoc :reward (-> % .-target .-value))}]]

        [:div "活动说明"
         [:textarea.textareaHeight
          {:value description
           :on-change #(swap! act-state assoc :description (-> % .-target .-value))}]]

        [:div
         [:button {:on-click #(upsert-act)} "确认"]
         [:button {:on-click #(reset! act-state nil)} "取消"]]]]])))

(defn acts-manage []
  (set-title! "社区活动管理")
  (get-act-list)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "社区活动管理"]]
     [:button.buttons.mt10 {:on-click #(reset! act-state {})} "添加"]
     [act-list-ul]
     (when @act-state
       [act-edit-dialog])
     [loading-spinner @loading?]]))

