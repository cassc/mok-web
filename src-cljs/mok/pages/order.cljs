(ns mok.pages.order
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
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

(defonce loading? (atom nil))

(defonce app-state (atom {:query-status "paid"}))

(defonce order-state (atom {}))
(defonce order-list-store (atom []))

(defn switch-to-panel [panel]
  (swap! app-state assoc :panel panel))

(defn load-orders! []
  (GET "/shop/order"
       {:response-format :json
        :params {:status (:query-status @app-state)}
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! order-list-store (:data %))})
        :error-handler default-error-handler}))

(defn update-order-cache [{:keys [id] :as order}]
  (swap! order-list-store (fn [xs] (mapv (fn [od] (if (= id (:id od)) order od)) xs))))

(defn update-order-product [{:keys [id ship_id ship_provider oid] :as op}]
  (POST "/shop/order/product"
        {:response-format :json
         :params {:id id :ship_id ship_id :ship_provider ship_provider :oid oid}
         :format :json
         :keywords? true
         :timeout 60000
         :handler (make-resp-handler
                   {:msg-fail "请求失败！"
                    :callback-success (fn [{:keys [data]}]
                                        (make-toast :info "保存成功！")
                                        (reset! order-state data)
                                        (update-order-cache data))})
         :error-handler default-error-handler}))

(defn update-order-status [{:keys [id]}]
  (POST "/shop/order/complete"
        {:response-format :json
         :params {:id id}
         :format :json
         :keywords? true
         :timeout 60000
         :handler (make-resp-handler
                   {:msg-fail "请求失败！"
                    :callback-success (fn [_]
                                        (make-toast :info "保存成功！")
                                        (swap! order-state assoc :status "complete")
                                        (update-order-cache @order-state))})
         :error-handler default-error-handler}))

(defn order-product-panel [prod]
  (let [prod-store (atom prod)]
    (fn [{:keys [title quantity ship_provider ship_id]}]
      [:div.order-edit__shipment
       [:div.order-edit__product-title (str title " x " quantity)]
       [:div.order-edit__label "物流商"]
       [:input {:type :text :value (or (:ship_provider @prod-store) "")
                :on-change #(swap! prod-store assoc :ship_provider (-> % .-target .-value))}]
       [:div.order-edit__label "物流编号"]
       [:input {:type :text :value (or (:ship_id @prod-store) "")
                :on-change #(swap! prod-store assoc :ship_id (-> % .-target .-value))}]
       (when (and
              (not
               (or
                (s/blank? (:ship_id @prod-store))
                (s/blank? (:ship_provider @prod-store))))
              (or
               (not= (:ship_id @prod-store) ship_id)
               (not= (:ship_provider @prod-store) ship_provider)))
         [:div.prod__btn-group
          [:a.btn-light {:href "javascript:;"
                         :on-click #(update-order-product @prod-store)}
           "保存"]])])))

(defn make-address [{:keys [address city province area]}]
  (s/join " " (filter identity [province city area address])))

(def m-status
  {"pending" "未支付"
   "paid" "已付款"
   "delivery" "已发货"
   "complete" "已完成"
   "cancel" "已取消"
   "hide" "已删除"})

(defn close-btn []
  [:div.order-edit__btn-group
   [:a.btn-light {:href "javascript:;"
                  :on-click #(do
                               (swap! app-state dissoc :panel)
                               (reset! order-state {}))}
    "返回订单列表"]])

;; (defn- order-status-editor [order]
;;   (let [state (atom order)]
;;     (fn [_]
;;       [:div.order-edit__field--editable
;;        [:select {:on-change #(let [idx (.. % -target -selectedIndex)
;;                                    status (-> (aget (.-target %) idx) .-value)]
;;                                (swap! state assoc :status status)
;;                                (load-orders!))
;;                  :value (:status @state)}
;;         (doall
;;          (for [[st txt] m-status]
;;            [:option {:value st :key (str "st." st)} txt]))]
;;        (when (not= (:status @state) (:status order))
;;          [:a.btn-light {:on-click #(update-order-status @order-state)} "保存"])
;;        [:a.btn-light {:on-click #(swap! order-state dissoc :edit-status?)} "取消"]])))

(defn order-panel []
  [:div.order-edit
   [close-btn]
   [:div.order-edit__fields
    [:div.order-edit__label "收货人姓名"]
    [:div.order-edit__field (:fullname @order-state)]
    [:div.order-edit__label "收货人电话"]
    [:div.order-edit__field (:phone @order-state)]
    [:div.order-edit__label "收货人地址"]
    [:div.order-edit__field (make-address @order-state)]
    [:div.order-edit__label "邮编"]
    [:div.order-edit__field (:zipcode @order-state)]
    [:div.order-edit__label "订单状态"]
    ;; [:div.order-edit__field
    ;;  (if (:edit-status? @order-state)
    ;;    [order-status-editor @order-state]
    ;;    [:span {:on-click #(when (js/confirm "修改订单状态？")
    ;;                         (swap! order-state assoc :edit-status? true))}
    ;;     (m-status (:status @order-state))])]
    [:div.order-edit__field (m-status (:status @order-state))]]
   [:div.order-edit__label "商品列表"]
   (doall
    (for [product (:products @order-state)]
      ^{:key (str "o." (:id @order-state) ".p." (:id product))}
      [order-product-panel product]))
   (when (= "delivery" (:status @order-state))
     [:a.btn-light {:href "javascript:;"
                    :on-click #(when (js/confirm "确认完成此订单？")
                                 (update-order-status (assoc @order-state :status "complete")))}
      "确认收货"])
   ;;[close-btn]
   ]) 

(defn order-list-panel []
  [:div.order
   [:div.order__query
    [:div "订单状态"]
    [:select {:on-change #(let [idx (.. % -target -selectedIndex)
                                status (-> (aget (.-target %) idx) .-value)]
                            (swap! app-state assoc :query-status status)
                            (load-orders!))
              :value (:query-status @app-state "all")}
     [:option {:value "all"} "所有"]
     (doall
      (for [[st txt] m-status]
        [:option {:value st :key (str "st." st)} txt]))]]
   [:div.order__list
    [:div.order__item.order__item--head
     [:div "ID"]
     [:div "姓名"]
     [:div "电话号码"]
     [:div "状态"]
     [:div "商品"]]
    (doall
     (for [{:keys [id fullname phone products status] :as order} @order-list-store]
       [:div.order__item.clickable
        {:key (str "od." id)
         :on-click #(do
                      (reset! order-state order)
                      (swap! app-state assoc :panel :edit))}
        [:div id]
        [:div fullname]
        [:div phone]
        [:div (m-status status)]
        [:div (s/join ", " (map (fn [{:keys [title quantity]}] (str title " x " quantity)) products))]]))]])

(defn order-manage []
  (set-title! "商品管理")
  (load-sellers!)
  (load-orders!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "商城"]
      "  >  "
      [:span.bkcrc-seceondT "订单发货"]]
     (case (:panel @app-state)
       :edit [order-panel]
       [order-list-panel])]))
 
