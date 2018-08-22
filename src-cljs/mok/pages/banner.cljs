(ns ^:figwheel-always mok.pages.banner
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [alandipert.storage-atom       :refer [local-storage]]
   [cljs.core.async :as async
    :refer [>! <! put! chan alts!]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                      default-error-handler ts->readable-time
                      format-date
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
(defonce bn-list-store (atom []))
(defonce banner-state (atom nil))

(defonce clear-div [:div {:style {:clear :both}}])

(defn get-bn-list []
  (reset! loading? true)
  (GET "/banner"
       {:handler (make-resp-handler
                  {:callback-success
                   #(reset! bn-list-store (:data %))})
        :error-handler (partial default-error-handler "/bnlist")
        :response-format :json
        :keywords? true
        :finally #(reset! loading? nil)}))

(defn- upsert-banner []
  (let [params @banner-state]
    (PUT "/banner"
         {:params params
          :format :json
          :response-format :json
          :keywords? true
          :timeout 60000
          :handler (make-resp-handler
                    {:msg-success "保存成功！" :msg-fail "请求失败！"
                     :callback-success #(do
                                          (get-bn-list)
                                          (reset! banner-state nil))})
          :error-handler default-error-handler})))

(defn- delete-banner [id]
  (DELETE
   "/banner"
   {:params {:id id}
    :format :json
    :response-format :json
    :keywords? true
    :timeout 60000
    :handler (make-resp-handler
              {:msg-success "删除成功！" :msg-fail "请求失败！"
               :callback-success #(get-bn-list)})
    :error-handler default-error-handler}))

(defn li-style
  [width & [more-style]]
  {:style (merge {:width width} more-style)})

(defn bn-item [bn]
  (fn [{:keys [id pos pic content title url] :as bn}]
    [:div.fd-div
     [:ul.fd-line 
      [:li.fd-li (li-style "20%") pos]
      [:li.fd-li (li-style "20%") [:img {:style {:width "120px" :height "80px"} :src pic}]]
      [:li.fd-li (li-style "20%" {:overflow-x :hidden}) content]
      [:li.fd-li (li-style "20%")
       [:p "标题："]
       [:p  title]
       [:p "链接地址："]
       [:p url]]
      [:li.fd-li (li-style "20%") [:span
                             [:a {:href "javascript:;"
                                  :on-click #(delete-banner id)}
                              "删除"]
                             " "
                             [:a {:href "javascript:;"
                                  :on-click #(reset! banner-state bn)}
                              "编辑"]]]]]))


(defn banner-list-ul []
  (fn []
    [:div {:style {:margin-top "10px"}}
     [:div.fd-divs
      [:ul.fd-lines
       [:li.fd-lis (li-style "20%") "位置"]
       [:li.fd-lis (li-style "20%") "图片"]
       [:li.fd-lis (li-style "20%") "文字"]
       [:li.fd-lis (li-style "20%") "配置"]
       [:li.fd-lis (li-style "20%") "操作"]]
      clear-div]
     (doall
      (for [bn @bn-list-store]
        ^{:key (:id bn)}
        [bn-item bn]))]))

(defn- banner-edit-dialog
  []
  (fn []
    (let [{:keys [id pos pic content title url]} @banner-state]
      [:div#banner-edit-dialog-parent.alertViewBox {:style {:display :block :text-align :left}}
       [:div#banner-edit-dialog.alertViewBoxContent
        [:h4 (if id "编辑" "添加") "Banner"]
        [:div.ct.edit
        [:div
         [:span.red "封面 (375px X 150px)"]
         (when pic
           [:img {:style {:width "120px" :height "80px"} :src pic}])
         [:input
          {:type :file
           :on-change (fn [e]
                        (let [file (first (array-seq (.. e -target -files)))]
                          (upload-static-file
                           {:file file
                            :callback-success
                            #(when-let [src (:data %)]
                               (swap! banner-state assoc :pic src))})))}]]
        [:div "标题"
         [:input
          {:type :text
           :value title
           :on-change #(swap! banner-state assoc :title (-> % .-target .-value))}]]
        [:div "文字"
         [:textarea
          {:value content
           :on-change #(swap! banner-state assoc :content (-> % .-target .-value))}]]
        [:div "链接"
         [:input {:type :text :value url :on-change #(swap! banner-state assoc :url (-> % .-target .-value))}]]
        [:div "位置"
         [:input {:type :number :value pos :on-change #(swap! banner-state assoc :pos (-> % .-target .-value))}]]
        [:div
         [:button {:on-click #(upsert-banner)} "确认"]
         [:button {:on-click #(reset! banner-state nil)} "取消"]]]]])))

(defn banner-manage []
  (set-title! "社区Banner管理")
  (get-bn-list)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "社区Banner管理"]]
     [:button.buttons.mt10 {:on-click #(reset! banner-state {})} "添加"]
     [banner-list-ul]
     (when @banner-state
       [banner-edit-dialog])
     [loading-spinner @loading?]]))

