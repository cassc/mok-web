(ns mok.pages.feedback-manage
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [cljs.core.async :as async
    :refer [>! <! put! chan alts!]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner default-error-handler ts->readable-time
                      error-toast make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?]]
   [mok.states :refer [appid->title]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session] 
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [alandipert.storage-atom       :refer [local-storage]]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [ajax.core :refer [GET POST PUT DELETE]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))

(defonce search-state (atom {:last nil :searching nil}))
(defonce fd-list-store (atom nil))
(defonce default-search-params {:lastid nil :cnt 20 :include-all false})
(defonce search-params (atom default-search-params))
(defonce loading? (atom nil))
(defonce replies (atom {}))
(defonce bclick-store (atom 0))
(defonce tags-store (local-storage (atom #{}) :tags-store))
(defonce selected-tags-store (local-storage (atom #{}) :selected-tags-store))
(defonce selected-status-store (atom #{"open"}))

(defn- fid->replies [fid]
  (when-not (or (get @replies fid) @loading?)
    (swap! loading? not)
    (GET "/fm/replies"
         {:params {:fid fid}
          :handler (make-resp-handler
                    {:callback-success
                     #(let [data (:data %)]
                        (swap! replies assoc fid data))})
          :error-handler (partial default-error-handler "/replies")
          :response-format :json
          :keywords? true
          :finally #(reset! loading? nil)})))

(defn get-tag-list []
  (GET "/fm/tag/list"
       {:handler (make-resp-handler
                  {:callback-success
                   #(let [tags (:data %)]
                      (reset! tags-store (set tags))
                      (reset! selected-tags-store (set tags)))})
        :error-handler (partial default-error-handler "/fm/tag/list")
        :response-format :json
        :keywords? true}))

(defn- valid-fd-list-params? [{:keys [status tags]}]
  (not (or
        (when (s/blank? status)
          (error-toast "状态不能为空"))
        (when (s/blank? tags)
          (error-toast "类别不能为空")))))

(defn- get-fd-list [& [replace?]]
  (when-not @session-expired?
    (let [status (s/join "," @selected-status-store)
          tags (s/join "," @selected-tags-store)
          params (assoc @search-params :status status :tags tags)]
      (when (valid-fd-list-params? params)
        (swap! search-state assoc :searching true)
        (GET "/fm/fdlist"
             {:params params
              :handler (make-resp-handler
                        {:callback-success
                         #(let [data (:data %)]
                            (if replace?
                              (reset! fd-list-store data)
                              (swap! fd-list-store concat data))
                            (when (< (count data) 20)
                              (swap! search-state assoc :last true)))})
              :error-handler (partial default-error-handler "/fdlist")
              :response-format :json
              :keywords? true
              :finally #(swap! search-state assoc :searching nil)})))))

(defn- load-more []
  (let [wh (.height (js/$ js/document))
        pos (.scrollTop (js/$ js/document))
        h (.height (js/$ js/window))]
    (when-not (or (< (+ h pos 10) wh)  (:last @search-state) (:searching @search-state))
      (swap! search-params assoc :lastid (-> @fd-list-store last :id))
      (get-fd-list))))

(let [updater (fn [key fid value]
                (fn [{:keys [id] :as fd}]
                  (if (= id fid)
                    (assoc fd key value)
                    fd)))
      status-updater (partial updater :status)
      tag-updater (partial updater :tag)]

  (defn- update-fd-status-in-store [fid status]
    (swap! fd-list-store (fn [fd-lists] (map (status-updater fid status) fd-lists))))
  (defn- update-fd-tag-in-store [fid tag]
    (swap! fd-list-store (fn [fd-lists] (map (tag-updater fid tag) fd-lists)))))



(defonce replies-fid-store (atom #{}))

(defn- send-reply [fid reply-store]
  (when-not @loading?
    (swap! loading? not)
    (let [content @reply-store]
      (PUT "/fm/reply"
           {:params {:fid fid :content content}
            :handler (make-resp-handler
                      {:callback-success
                       #(do
                          (swap! replies update fid conj {:content content :rtype "admin" :ts (.getTime (js/Date.))})
                          (update-fd-status-in-store fid "handled"))})
            :error-handler (partial default-error-handler "/reply")
            :response-format :json
            :format :json
            :keywords? true
            :finally #(do
                        (reset! reply-store nil)
                        (reset! loading? nil))}))))

(defn li-style
  [width & [more-style]]
  {:style (merge {:width width} more-style)})

(defn update-fd-status [e fid status]
  (let [now (.getTime (js/Date.))]
    (if (pos? (- now @bclick-store 1000))
      (when-not @loading?
        (reset! bclick-store now)
        (swap! loading? not)
        (POST "/fm/feedback"
              {:params {:id fid :status status}
               :handler (make-resp-handler
                         {:callback-success
                          #(update-fd-status-in-store fid status)})
               :error-handler (partial default-error-handler "/fm/feedback")
               :response-format :json
               :format :json
               :keywords? true
               :finally #(reset! loading? nil)}))
      (make-toast :info "同学，你慢慢点！")))
  (.preventDefault e)
  (.stopPropagation e)
  nil)

(defonce clear-div [:div {:style {:clear :both}}])
(defonce selected-fids (atom #{}))

(defn toggle-selected-fid [fid]
  (swap! selected-fids (fn [sx] (if (sx fid) (disj sx fid) (conj sx fid)))))

(defn fd-div []
  (let [show-replies? (atom nil)
        reply-store (atom nil)]
    (fn [{:keys [id aid appid content ts nts ua contact auid status account tag] :as fd}]
      (let [user-id (or (when (and aid (pos? aid)) aid) (:id account))]
        [:div.fd-div 
         [:ul.fd-line
          [:li.fd-li (li-style "5%")
           [:input {:type :checkbox :on-change #(toggle-selected-fid id) :checked (@selected-fids id)}]]
          [:li.fd-li (li-style "10%") user-id]
          [:li.fd-li (li-style "10%") [:span (or contact (:haier account) (:phone account))]]
          [:li.fd-li (assoc (li-style "20%" {:overflow-x :hidden}) :title ua) (str (subs ua 0 25) "...")]
          [:li.fd-li (li-style "10%") (subs (ts->readable-time ts) 0 10)]
          [:li.fd-li (assoc (li-style "19%")
                      :on-click #(when (and user-id (= "handled" status))
                                   (swap! show-replies? not) nil))
           content]
          [:li.fd-li (li-style "8%") tag]
          [:li.fd-li (li-style "8%") (case status
                                 "open" [:span {:style {:color "red" :padding "2px"}} "待处理"]
                                 "handled" [:span {:style {:color "green" :padding "2px"}} "已处理"]
                                 "closed" [:span {:style {:color "#999999" :padding "2px"}} "已关闭"])]
          [:li.fd-li (li-style "10%")
           (when (= "open" status)
             [:span
              [:input {:type "button" :value "关闭"
                       :title "关闭后，用户无法看到此反馈"
                       :style {:min-width "40px"}
                       :on-click #(update-fd-status % id "closed")}]
              [:input {:type "button" :value "回复"
                       :style {:min-width "40px"}
                       :on-click #(when user-id 5(swap! show-replies? not) nil)}]])]]
         clear-div
         (when @show-replies?
           (fid->replies id)
           [:div.alertViewBox {:style {:display :block :text-align :left}}
           [:div#user-info-dialog-01.alertViewBoxContent
           [:h4 "回复"]
           [:div.ct
            [:ul {:style {:overflow "hidden" }}
             [:li (li-style "100%" {:float :left}) [:span {:style {:font-weight :bold}} content]]]
            (doall
             (for [reply (get @replies id)
                   :let [{:keys [content ts rtype]} reply
                         client? (= "client" rtype)]]
               ^{:key reply}
               [:div {:style {:clear :both :overflow :auto :color (if client? "#404040" "#494951")}}
                [:ul 
                 [:li
                  (str rtype (str "于" (ts->readable-time ts) "回复："))]
                 [:li {:style {:margin-top "5px"}}
                  content]]]))
            clear-div
            (when-not (= "closed" status)
              [:div {:style {:margin-bottom "5px" :margin-top "5px" :border-radius "3px"}}
               [:textarea {:style {:resize :none :width "100%" :height "100px" :display :inline-block :vertical-align :bottom :margin-right "5px"}
                           :value @reply-store
                           :on-change #(reset! reply-store (-> % .-target .-value))}]
               [:input.buttons.mt10 {:type :button
                        :style {:vertical-align :bottom :margin-right "5px"}
                        :value "发送"
                        :on-click #(when-not (s/blank? @reply-store)
                                     (send-reply id reply-store))}]
               [:input.buttons.mt10 {:type :button
                        :style {:vertical-align :bottom :margin-right "5px"}
                        :value "关闭"
                        :on-click #(reset! show-replies? nil)}]])
            clear-div]]])]))))

(def fd-list-ul
  (with-meta
    (fn []
      [:div {:style {:margin-top "10px"}}
       [:div.fd-divs
        [:ul.fd-lines
         [:li.fd-lis (li-style "5%") "选择"]
         [:li.fd-lis (li-style "10%") "aid"]
         [:li.fd-lis (li-style "10%") "联系方式"]
         [:li.fd-lis (li-style "20%") "UA"]
         [:li.fd-lis (li-style "10%") "时间"]
         [:li.fd-lis (li-style "19%") "内容"]
         [:li.fd-lis (li-style "8%") "类别"]
         [:li.fd-lis (li-style "8%") "状态"]
         [:li.fd-lis (li-style "10%") "操作"]]
        clear-div]
       (doall
        (for [fd @fd-list-store]
          ^{:key (:id fd)}
          [fd-div fd]))])
    {:component-will-mount #(.addEventListener js/window "scroll" load-more)
     :component-will-unmount #(.removeEventListener js/window "scroll" load-more)}))

(letfn [(reset-common-and-reload []
          (swap! search-params assoc :lastid nil)
          (swap! search-state assoc :last nil)
          (reset! replies nil)
          (get-fd-list true)
          nil)]
  (defn- reload-and-get-fd-list
    []
    (reset-common-and-reload)))

(defn- toggle-tag-selection [tag]
  (t/info tag)
  (swap! selected-tags-store (fn [tags] (if (tags tag) (disj tags tag) (conj tags tag)))))

(defonce new-tag-store (atom nil))

(defn tag-list-div []
  [:div#feedback-manage-tag-list-div
   [:h4 "类别"]
   [:select {:name "tag" :multiple "multiple"
             :value @selected-tags-store
             :on-change #(doseq [opt (array-seq (-> % .-target .-options))]
                           (if (.-selected opt)
                             (swap! selected-tags-store conj (.-value opt))
                             (swap! selected-tags-store disj (.-value opt))))}
    (doall
     (for [tag @tags-store]
       ^{:key tag}
       [:option tag]))]])

(defn status-list-div []
  [:div#feedback-manage-status-list-div
   [:h4 "状态"]
   [:select {:name "status"
             :multiple "multiple"
             :value @selected-status-store
             :on-change #(doseq [opt (array-seq (-> % .-target .-options))]
                           (if (.-selected opt)
                             (swap! selected-status-store conj (.-value opt))
                             (swap! selected-status-store disj (.-value opt))))}
    [:option {:value "closed"} "已关闭"]
    [:option {:value "open"} "待处理"]
    [:option {:value "handled"} "已处理"]]])

(defonce changed-tag-store (atom "无类别"))

(defn change-tag-for-fids! []
  (let [fids (seq  @selected-fids)
        tag @changed-tag-store]
    (when (and fids (not @loading?))
      (swap! loading? not)
      (POST "/fm/feedback"
            {:params {:ids fids :tag @changed-tag-store}
             :handler (make-resp-handler
                       {:callback-success
                        #(run! (fn [fid] (update-fd-tag-in-store fid tag)) fids)})
             :error-handler (partial default-error-handler "/fm/feedback")
             :response-format :json
             :format :json
             :keywords? true
             :finally #(reset! loading? nil)}))))

(defonce show-cat-dialog (atom nil) )

(defn feedback-manage []
  (set-title! "用户反馈")
  (get-tag-list)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "用户反馈"]]
     [:div#feedback-manage-div
      [tag-list-div]
      [status-list-div]
      [:button.buttons.mt24 {:on-click #(reload-and-get-fd-list)} "查询"]]
     [:hr {:style {:margin-top "10px" :margin-bottom "10px"}}]
     [:div#feedback-manage-div-2
      (when @show-cat-dialog
        [:div.alertViewBox {:style {:display :table :text-align :left}}
         [:div#manage-dialog.alertViewBoxContent
          [:h4 "添加问题到类别"]
          [:div.ct
           [:div.setDiv
            [:span "添加所选问题到已有类别"]
            [:select {:name "tag"
                      :value @changed-tag-store
                      :on-change #(let [idx (.. % -target -selectedIndex)]
                                    (reset! changed-tag-store (-> (aget (.-target %) idx) .-value)))}
             (doall
               (for [tag @tags-store]
                 ^{:key tag}
                 [:option tag]))]
            [:button.ml10 {:on-click #(do (change-tag-for-fids!)
                                          (reset! show-cat-dialog nil))} "确认"]
            ]
           [:div.setDiv
            [:span "添加所选问题到新增类别"]
            [:input {:type :text
                     :value @new-tag-store
                     :on-change #(reset! new-tag-store (-> % .-target .-value))}]
            [:button.ml10 {:on-click #(when-not (s/blank? @new-tag-store)
                                        (swap! tags-store conj @new-tag-store)
                                        (reset! changed-tag-store @new-tag-store)
                                        (reset! new-tag-store nil)
                                        (change-tag-for-fids!)
                                        (reset! show-cat-dialog nil))} "确认"]
            ]
           ;;[:button.buttons.mr10 {:on-click #(change-tag-for-fids!)} "确认"]
           [:button.buttons {:on-click #(reset! show-cat-dialog nil)} "关闭"]]]])
      [:button.fr {:disabled (when-not (seq @selected-fids) "disabled")
                   :on-click #(when (seq @selected-fids) (reset! show-cat-dialog true))} "添加问题到类别"]
      ]
     [fd-list-ul]
     [loading-spinner (:searching @search-state)]]))
