(ns ^:figwheel-always mok.pages.shop
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                      default-error-handler ts->readable-time
                      pikaday-construct format-date
                      error-toast make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                      upload-static-file load-sellers!]]
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

(defonce loading? (atom nil))

(defonce app-state (atom {}))

(defonce seller-state (atom {}))

(defn switch-to-panel [panel]
  (swap! app-state assoc :panel panel))

(defn valid-seller? [_]
  true)

(defn add-seller [{:keys [title owner phone description license] :as params}]
  (PUT "/seller"
       {:params params
        :format :json
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-success "添加成功！" :msg-fail "请求失败！"
                   :callback-success #(do
                                        (load-sellers!)
                                        (switch-to-panel :home)
                                        (reset! seller-state {}))})
        :error-handler default-error-handler}))

(defn save-seller [{:keys [phone fullname id] :as params}]
  (POST "/seller"
        {:params params
         :format :json
         :response-format :json
         :keywords? true
         :timeout 60000
         :handler (make-resp-handler
                   {:msg-success "保存成功！" :msg-fail "请求失败！"
                    :callback-success #(do
                                         (load-sellers!)
                                         (switch-to-panel :home)
                                         (reset! seller-state {}))})
         :error-handler default-error-handler}))

(defn delete-seller [{:keys [id] :as params}]
  {:pre [(pos? id)]}
  (DELETE "/seller"
        {:params {:id id}
         :format :json
         :response-format :json
         :keywords? true
         :timeout 60000
         :handler (make-resp-handler
                   {:msg-success "删除成功！" :msg-fail "请求失败！"
                    :callback-success #(do
                                         (load-sellers!)
                                         (switch-to-panel :home)
                                         (reset! seller-state {}))})
         :error-handler default-error-handler}))

(defn seller-panel []
  (let [edit? (:id @seller-state)]
    [:div.seller
     [:h3 (if edit? "修改卖家" "添加卖家")]
     [:div.seller__details
      [:div.seller__label "店名"]
      [:input {:type :text :value (:title @seller-state "") :on-change #(swap! seller-state assoc :title (-> % .-target .-value))}]
      [:div.seller__label "店主姓名"]
      [:input {:type :text :value (:owner @seller-state "") :on-change #(swap! seller-state assoc :owner (-> % .-target .-value))}] 
      [:div.seller__label "电话号码"]
      [:input {:type :text :value (:phone @seller-state "") :on-change #(swap! seller-state assoc :phone (-> % .-target .-value))}]
      [:div.seller__label "店铺介绍"]
      [:textarea {:value (:description @seller-state "") :on-change #(swap! seller-state assoc :description (-> % .-target .-value))}]
      [:div.seller__btn-group
       [:a.btn-light {:href "javascript:;"
                      :on-click #(when (valid-seller? @seller-state)
                                   ((if edit? save-seller add-seller) @seller-state))}
        (if edit? "保存" "添加")]
       (when edit?
         [:a.btn-light {:href "javascript:;"
                        :on-click #(when (js/confirm "确认删除此店铺？")
                                     (delete-seller @seller-state))}
          "删除"])
       [:a.btn-light {:href "javascript:;" :on-click #(do
                                                        (swap! app-state dissoc :panel)
                                                        (reset! seller-state {}))} "取消"]]]]))

(defn seller-list-panel []
  [:div.seller
   [:div.seller-list
    [:div.seller-list__header "店名"] [:div.seller-list__header "电话号码"]]
   (doall
    (for [{:keys [id title description phone owner] :as seller} @seller-list-store]
      [:div.seller-list.clickable
       {:key (str "shop." id)
        :on-click #(do
                     (reset! seller-state seller)
                     (swap! app-state assoc :panel :edit))}
       [:div title]
       [:div phone]]))])

(defn shop-manage []
  (set-title! "卖家管理")
  (load-sellers!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "商城"]
      "  >  "
      [:span.bkcrc-seceondT "卖家管理"]]
     (when-not (:panel @app-state)
       [:button.buttons.mt10 {:on-click #(swap! app-state assoc :panel :add)} "添加"])
     (case (:panel @app-state)
       :add [seller-panel]
       :edit [seller-panel]
       [seller-list-panel])]))

(defn order-manage []
  (set-title! "订单管理") 
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "商城"]
      "  >  "
      [:span.bkcrc-seceondT "订单管理"]]
     [:button.buttons.mt10 {:on-click #(reset! app-state {})} "添加"]
     ]))

