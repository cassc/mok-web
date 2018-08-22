(ns ^:figwheel-always mok.pages.report
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [alandipert.storage-atom       :refer [local-storage]]
   [cljs.core.async :as async
    :refer [>! <! put! chan alts!]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                      default-error-handler ts->readable-time
                      format-date status-text
                      error-toast make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                      spacer upload-static-file]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [ajax.core :refer [GET POST PUT DELETE]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]]))

(defonce loading? (atom nil))
(defonce report-list-store (atom []))
(defonce blamed-store (atom nil))
(defonce report-state (atom nil))
(defonce clear-div [:div {:style {:clear :both}}])
(defonce displayed-status-store (atom 0))

(def result-text
  {"disable_account" "禁用账号"
   "disable_mblog"   "禁言"
   "delete"          "删除文章"
   "ignore"          "忽略"
   "pending"         "等待处理"})

(defn- blamed? [{:keys [result]}]
  (#{"disable_account" "disable_mblog" "delete"} result))

(defn get-report-list []
  (reset! loading? true)
  (GET (str "/report?status=" @displayed-status-store)
       {:handler (make-resp-handler
                  {:callback-success
                   #(reset! report-list-store (:data %))})
        :error-handler (partial default-error-handler "/reportlist")
        :response-format :json
        :keywords? true
        :finally #(reset! loading? nil)}))

(defn get-blamed [target]
  (reset! loading? true)
  (GET "/blamed"
       {:handler (make-resp-handler
                  {:callback-success
                   #(reset! blamed-store (:data %))})
        :params {:target_aid target}
        :error-handler (partial default-error-handler "/blamed")
        :response-format :json
        :keywords? true
        :finally #(reset! loading? nil)}))

(defn- handle-report []
  (when-not @loading?
    (reset! loading? true)
    (POST "/report"
          {:params @report-state
           :format :json
           :handler (make-resp-handler
                     {:callback-success
                      #(do
                         (get-report-list)
                         (reset! report-state nil))})
           :error-handler (partial default-error-handler "/report")
           :response-format :json
           :keywords? true
           :finally #(reset! loading? nil)})))

(defn- upsert-report []
  (let [params @report-state]
    (PUT "/report"
         {:params params
          :format :json
          :response-format :json
          :keywords? true
          :timeout 60000
          :handler (make-resp-handler
                    {:msg-success "保存成功！" :msg-fail "请求失败！"
                     :callback-success #(do
                                          (get-report-list)
                                          (reset! report-state nil))})
          :error-handler default-error-handler})))

(defn- delete-report [id]
  (DELETE
   "/report"
   {:params {:id id}
    :format :json
    :response-format :json
    :keywords? true
    :timeout 60000
    :handler (make-resp-handler
              {:msg-success "删除成功！" :msg-fail "请求失败！"
               :callback-success #(get-report-list)})
    :error-handler default-error-handler}))

(defn li-style
  [width & [more-style]]
  {:style (merge {:width width} more-style)})

(defn report-item [report]
  (fn [{:keys [id mid msg pic type aid target_aid uid target_uid ts result] :as report}]
    [:div.fd-div
     [:ul.fd-line 
      [:li.fd-li (li-style "5%") id]
      [:li.fd-li (li-style "10%") (case type "post" "主题" "reply" "回复")]
      [:li.fd-li (li-style "20%")
       [:img {:style {:width "120px" :height "120px" :border-radius "5px"} :src (str "/mm/pic/" pic)}]
       [:p msg]]
      [:li.fd-li (li-style "10%") aid]
      [:li.fd-li (li-style "10%") uid]
      [:li.fd-li (li-style "10%") target_aid]
      [:li.fd-li (li-style "10%") target_uid]
      [:li.fd-li (li-style "10%") (format-date ts)]
      [:li.fd-li (li-style "10%") (result-text result)]
      [:li.fd-li (li-style "5%")
       [:a {:href "javascript:;"
            :on-click #(reset! report-state report)}
        "处理"]]]]))


(defn report-list-ul []
  (fn []
    [:div {:style {:margin-top "10px"}}
     [:div.fd-divs
      [:ul.fd-lines
       [:li.fd-lis (li-style "5%") "ID"]
       [:li.fd-lis (li-style "10%") "类型"]
       [:li.fd-lis (li-style "20%") "被举报内容"]
       [:li.fd-lis (li-style "10%") "举报人AID"]
       [:li.fd-lis (li-style "10%") "举报人手机号"]
       [:li.fd-lis (li-style "10%") "被举报人AID"]
       [:li.fd-lis (li-style "10%") "被举报人手机号"]
       [:li.fd-lis (li-style "10%") "举报时间"]
       [:li.fd-lis (li-style "10%") "状态"]
       [:li.fd-lis (li-style "5%") "操作"]]
      clear-div]
     (doall
      (for [report @report-list-store]
        ^{:key (:id report)}
        [report-item report]))]))

(defn- report-edit-dialog
  [target_aid]
  (get-blamed target_aid)
  (fn [target_aid]
    [:div#report-edit-dialog-parent.alertViewBox {:style {:display :block :text-align :left}}
     [:div#report-edit-dialog.alertViewBoxContent
      [:h4 "举报处理"]
      [:div.ct
      [:table.report-table
       [:thead
        [:tr
         [:th "昵称"] [:th "被举报次数"] [:th "违规次数"] [:th "最近处理方式"] [:th "当前状态"]]]
       [:tbody
        [:tr
         [:td (get-in @blamed-store [:account :nickname])]
         [:td (count (get-in @blamed-store [:reports]))]
         [:td (count (filter blamed? (get-in @blamed-store [:reports])))]
         [:td (result-text (-> @blamed-store :reports last :result))]
         [:td (status-text (get-in @blamed-store [:account :status]))]]]]
      [:div.report-diva
       [:span "本次处理方式"]
       [:select
        {:name "tag"
         :value (@report-state :result "pending")
         :on-change #(let [idx (.. % -target -selectedIndex)]
                       (swap! report-state assoc :result (-> (aget (.-target %) idx) .-value)))}
         (doall
         (for [[k v] result-text]
           [:option {:value k} v]))]]
      [:div {:style {:font-size :x-small}}
       [:p "说明1：被禁言的用户不允许打卡；被禁用的用户不允许登录；"]
       [:p "说明2：可在用户管理页搜索并修改用户状态"]]
      [:div
       [:button {:on-click #(handle-report)} "确认"]
       [:button {:on-click #(reset! report-state nil)} "取消"]]]]]))

(defn report-manage []
  (set-title! "举报管理")
  (get-report-list)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "举报管理"]]
     [:select.mt10 {:name "status"
               :value (or @displayed-status-store 0)
               :on-change #(let [idx (.. % -target -selectedIndex)]
                             (reset! displayed-status-store (-> (aget (.-target %) idx) .-value))
                             (get-report-list))}
      [:option {:value 0} "待处理"]
      [:option {:value 1} "已处理"]]
     [report-list-ul]
     (when @report-state
       [report-edit-dialog (:target_aid @report-state)])
     [loading-spinner @loading?]]))


