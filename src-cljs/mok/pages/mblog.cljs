(ns ^:figwheel-always mok.pages.mblog
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
                      spacer]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [cljs-pikaday.reagent :as pikaday]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [ajax.core :refer [GET POST PUT DELETE]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long to-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]))

(defonce loading? (atom nil))
(defonce search-state (atom {:last nil}))
(defonce start-date (atom (js/Date. (- (to-long (now)) (* 7 3600 1000 24)))))
(defonce end-date (atom (js/Date.)))
(defonce default-mblog-list-query-params {:start nil :end nil})
(defonce mblog-list-query-params (atom default-mblog-list-query-params))
(defonce mblog-list-store (atom nil))
(defonce display-num-state (atom 10))
(defonce mblog-details (atom nil))
(defonce with-mblog-weight-dialog (atom nil))
(defonce mblog-ids-to-delete (atom #{}))
(defonce mblog-reply-store (atom {}))

(defn- toggle-mid-to-delete [mid]
  (swap! mblog-ids-to-delete (fn [xs] (if (xs mid) (disj xs mid) (conj xs mid)))))

(defn update-cached-md [{:keys [id] :as md}]
  (swap!
   mblog-list-store
   (fn [xs]
     (mapv (fn [mbo]
             (if (= id (:id mbo))
               (merge mbo md)
               mbo))
           xs))))

(defn reload-mblog-list []
  (let [params @mblog-list-query-params]
    (when-not @loading?
      (swap! loading? not)
      (GET "/mm/mblog"
           {:params (assoc params
                           :pop-only (if (:pop-only params) 1 0)
                           :start (format-date @start-date)
                           :end (format-date @end-date))
            :handler (make-resp-handler
                      {:callback-success
                       #(let [data (:data %)]
                          (reset! mblog-list-store data))})
            :error-handler (partial default-error-handler "/mm/mblog")
            :response-format :json
            :keywords? true
            :finally #(reset! loading? nil)}))))

(defn loag-mblog-reply [parent-id]
  (GET "/mm/mblog-reply"
       {:params {:parent_id parent-id}
        :handler (make-resp-handler
                  {:callback-success
                   #(let [data (:data %)]
                      (swap! mblog-reply-store assoc parent-id data))})
        :error-handler (partial default-error-handler "/mm/mblog-reply")
        :response-format :json
        :keywords? true}))

;; (defn remove-local-mblog-by-id! [id]
;;   (swap! mblog-list-store (fn [xs] (remove #(= id (:id %)) xs))))

(defn delete-mblog [id]
  (DELETE (str "/mm/mblog/" id)
          {:handler (make-resp-handler
                     {:callback-success
                      #(do (reload-mblog-list)
                           (make-toast :info (str "删除" id)))})
           :response-format :json
           :keywords? true}))

(defn- load-more []
  (let [wh (.height (js/$ js/document))
        pos (.scrollTop (js/$ js/document))
        h (.height (js/$ js/window))]
    (when (and (>= (+ h pos 10) wh)
               (< @display-num-state (count @mblog-list-store)))
      (swap! display-num-state (partial + 10)))))

(defn update-md [{:keys [id] :as md}]
  (when-not @loading?
    (reset! loading? true)
    (POST "/mm/mblog"
            {:handler (make-resp-handler
                       {:callback-success #(do
                                             (update-cached-md md)
                                             (reset! with-mblog-weight-dialog nil))})
             :params md
             :format :json
             :response-format :json
             :keywords? true
             :finally (reset! loading? nil)})))

(defn- mblog-details-dialog
  []
  (fn []
    [:div.alertViewBox {:style {:display :block :text-align :left}}
     [:div#mblog-details-dialog.alertViewBoxContent
     [:h4 "删除"]
      (let [{:keys [id msg account_id ts pic nickname company phone haier likes replies ispop pop_weight weight]} @mblog-details]
        [:div.ct
         [:div#mblog-details-dialog-head-img [:img {:style {:width "120px" :height "120px" :border-radius "5px":margin-right "10px"} :src (str "/mm/pic/" pic)}]
          [:span#mblog-details-dialog-head-img-span
           [:span msg]
           [:input {:type :checkbox :on-change #(toggle-mid-to-delete id)}]]]
         [:div#mblog-details-dialog-tail
          (doall
           (for [{:keys [msg id account_id role]} replies]
             ^{:key [id]}
             [:div#mblog-details-dialog-tail-div
              [:span#mblog-details-dialog-tail-div-span
               [:span (:nickname role)]
               [:span ": "]
               [:span msg]
               [:input#mblog-details-dialog-input {:type :checkbox :on-change #(toggle-mid-to-delete id)}]]]))]
         [:button#mblog-details-dialog-buttona {:on-click #(when-let [ids (seq @mblog-ids-to-delete)]
                                (run! delete-mblog ids)
                                (reset! mblog-details nil)
                                (reset! mblog-ids-to-delete #{}))}
          "删除"]
         [:button#mblog-details-dialog-buttonb {:on-click #(reset! mblog-details nil)} "取消"]])]]))


(defn- mblog-weight-dialog
  []
  (fn []
    [:div.alertViewBox {:style {:display :block :text-align :left}}
     (let [{:keys [id nickname ispop pop_weight weight]} @with-mblog-weight-dialog]
       [:div#mblog-details-dialog.alertViewBoxContent
       [:h4 "设置"]
        [:div.ct
         [:div
          [:p
           [:span [:input {:type :checkbox
                           :checked (= 1 ispop)
                           :on-change #(swap! with-mblog-weight-dialog assoc :ispop (if (= 1 ispop) 0 1))}]
            "达人"]]
          (when (= 1 ispop)
            [:p
             [:span "达人权重："]
             [:input {:type :number :placeholder "达人权重"
                      :value pop_weight
                      :on-change #(swap! with-mblog-weight-dialog assoc :pop_weight (-> % .-target .-value))}]])]
         [:p
          [:span "打卡权重："]
          [:input {:type :number :placeholder "打卡权重"
                   :value weight
                   :on-change #(swap! with-mblog-weight-dialog assoc :weight (-> % .-target .-value))}]]
         [:button#mblog-details-dialog-buttona {:on-click #(update-md (select-keys @with-mblog-weight-dialog [:id :weight :pop_weight :ispop]))} "保存"]
         [:button#mblog-details-dialog-buttonb {:on-click #(reset! with-mblog-weight-dialog nil)} "取消"]]])]))

(def mblog-list
  (with-meta
    (fn []
      [:div
       [:table.manage-table {:cell-padding 0 :cell-spacing 0}
        [:thead
         [:tr
          [:th [:a {:href "javascript:;"} "MID"]]
          [:th [:a {:href "javascript:;"} "打卡内容详情"]]
          [:th [:a {:href "javascript:;"}
                [:p "达人权重/"]
                [:p "打卡权重"]]]
          [:th [:a {:href "javascript:;"} "昵称"]]
          [:th [:a {:href "javascript:;"} "手机号"]]
          [:th [:a {:href "javascript:;"} "发表时间"]]
          [:th [:a {:href "javascript:;"} "占赞数"]]
          [:th [:a {:href "javascript:;"} "评论数"]]
          [:th [:a {:href "javascript:;"} "操作"]]]]
        [:tbody
         (doall
          (for [{:keys [id msg account_id ts pic nickname company phone haier likes replies  weight ispop pop_weight] :as mblog} (take @display-num-state @mblog-list-store)]
            [:tr {:key id}
             [:td [:a {:href "javascript:;"} id]]
             [:td {:style {:max-width "80px" :overflow-x "hidden" :padding "2px"}}
              [:div 
               (when-not (s/blank? pic)
                 [:img {:style {:max-width "120px" :max-height "120px" :border-radius "5px"} :src (str "/mm/pic/" pic)}])
               [:div {:style {:display :flex :font-weight :bold :text-align :left
                              :padding-top "1rem"}}
                msg]
               (doall
                (for [{:keys [msg id]} replies]
                  [:div {:key (str "mr-" id) :style {:display :flex :justify-content :space-between :text-align :left :padding-top "1rem"}} 
                   [:div "- " msg]
                   [:a {:href "javascript:;"
                        :on-click #(when (js/confirm (str "删除此评论？\n" msg)) 
                                     (delete-mblog id))
                        :style {:display :block}} 
                    [:i.fa.fa-trash]]]))]]
             [:td (str (if (= 1 ispop) (or pop_weight 0) "NA") "/" (or weight 0))]
             [:td {:style {:max-width "20px" :overflow-x "hidden"}} nickname]
             [:td [:a {:href "javascript:;"} (or phone haier)]]
             [:td {:style {:max-width "30px" :overflow-x "hidden"}} [:a {:href "javascript:;"} (ts->readable-time ts)]]
             [:td [:a {:href "javascript:;"} (count likes)]]
             [:td [:a {:href "javascript:;"} (count replies)]]
             [:td{:style {:max-width "30px" :overflow-x "hidden"}}
              [:a {:href "javascript:;" :on-click #(reset! with-mblog-weight-dialog mblog)} "设置"]
              [:br]
              [:a {:href "javascript:;" :on-click #(reset! mblog-details mblog)} "删除"]]]))]]])
    {:component-will-mount #(.addEventListener js/window "scroll" load-more)
     :component-will-unmount #(.removeEventListener js/window "scroll" load-more)}))

(defn- start-pickday []
  [pikaday/date-selector
   {:date-atom start-date
    :input-attrs {:style {:width "80px"}}
    :pikaday-attrs pikaday-construct}])
(defn- end-pickday []
  [pikaday/date-selector
   {:date-atom end-date
    :input-attrs {:style {:width "80px"}}
    :pikaday-attrs pikaday-construct}])

(defn mblog-manage []
  (set-title! "社区内容管理")
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "社区内容管理"]]
     [:div.menu-div
      [:div.menu-search
      [start-pickday]
      [:span "至"]
      [end-pickday]
      ]
      [:div.menu-search
      [:span "内容"]
      [:input {:type :text :placeholder "内容关键字"
               :value (:content @mblog-list-query-params)
               :on-change #(swap! mblog-list-query-params assoc :content (-> % .-target .-value))}]
      ]
      [:div.menu-search
      [:span "昵称"]
      [:input {:type :text :placeholder "昵称"
               :value (:nickname @mblog-list-query-params)
               :on-change #(swap! mblog-list-query-params assoc :nickname (-> % .-target .-value))}]
      ]
      [:div.menu-search
      [:span [:input {:type :checkbox
                      :checked (:pop-only @mblog-list-query-params)
                      :on-change #(swap! mblog-list-query-params update-in [:pop-only] not)}]
       "达人"]
      ]
      [:div.menu-search
      [:span "打卡权重"]
       [:input {:type :number :placeholder "打卡权重"
                :value (:weight @mblog-list-query-params)
                :on-change #(swap! mblog-list-query-params assoc :weight (-> % .-target .-value))}]
      ]
      [:button {:on-click #(reload-mblog-list)} "查询"]]
     [mblog-list]
     (when @mblog-details
       [mblog-details-dialog])
     (when @with-mblog-weight-dialog
       [mblog-weight-dialog])
     [loading-spinner @loading?]]))
