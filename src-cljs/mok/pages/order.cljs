(ns mok.pages.order
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; STATE
(defonce loading? (atom nil))

(defonce app-state (atom {:query-status "paid" :query-page 1 :return-status "pending" :return-page 1}))

(defonce order-state (atom {}))
(defonce order-list-store (atom []))
(defonce order-return-list-store (atom []))
(defonce return-state (atom {}))


(defn switch-to-panel [panel]
  (swap! app-state assoc :panel panel))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; NETWORK / CONTROLS
(defn load-orders! []
  (GET "/shop/order"
       {:response-format :json
        :params {:status (:query-status @app-state)
                 :page (:query-page @app-state)}
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! order-list-store (:data %))})
        :error-handler default-error-handler}))

(defn load-order-returns! []
  (GET "/shop/order-return"
       {:response-format :json
        :params {:status (:return-status @app-state)
                 :page (:return-page @app-state)}
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! order-return-list-store (:data %))})
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

(def return-status
  {"pending" "未处理"
   "processing" "退款中"
   "success" "成功"
   "reject" "已拒绝"})

(defn handle-order-return [result id]
  (POST "/shop/order-return"
        {:response-format :json
         :params {:id id :status result}
         :format :json
         :keywords? true
         :timeout 60000
         :handler (make-resp-handler
                   {:msg-fail "请求失败！"
                    :callback-success (fn [_]
                                        (make-toast :info "保存成功！")
                                        (swap! return-state assoc :status result))})
         :error-handler default-error-handler}))

(def confirm-return! (partial handle-order-return "success"))

(def reject-return! (partial handle-order-return "reject"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI

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


(defn close-btn []
  [:div.order-edit__btn-group
   [:a.btn-light {:href "javascript:;"
                  :on-click #(do
                               (swap! app-state dissoc :panel)
                               (reset! order-state {}))}
    "返回订单列表"]])

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
                            (swap! app-state assoc :query-status status :query-page 1)
                            (load-orders!))
              :value (:query-status @app-state "all")}
     [:option {:value "all"} "所有"]
     (doall
      (for [[st txt] m-status]
        [:option {:value st :key (str "st." st)} txt]))]]
   [:div.order__list
    [:div.order__item.order__item--head
     ;; [:div "ID"]
     [:div "订单号"]
     [:div "姓名"]
     [:div "电话号码"]
     [:div "状态"]
     [:div "商品"]]
    (doall
     (for [{:keys [id ouid fullname phone products status] :as order} @order-list-store]
       [:div.order__item.clickable
        {:key (str "od." id)
         :on-click #(do
                      (reset! order-state order)
                      (swap! app-state assoc :panel :edit))}
        ;;[:div id]
        [:div ouid]
        [:div fullname]
        [:div phone]
        [:div (m-status status)]
        [:div (s/join ", " (map (fn [{:keys [title quantity]}] (str title " x " quantity)) products))]]))]
   [:div.order__paginator
    (if (> (:query-page @app-state) 1)
      [:a.btn.btn-light {:href "javascript:;" :on-click #(do
                                                           (swap! app-state update :query-page dec)
                                                           (load-orders!))}
       "上一页"]
      [:div.btn.btn-light.order__paginator--disabled "上一页"])
    [:span (:query-page @app-state 1)]
    (if (= (count @order-list-store) 20)
      [:a.btn.btn-light {:href "javascript:;" :on-click #(do
                                                           (swap! app-state update :query-page inc)
                                                           (load-orders!))}
       "下一页"]
      [:div.btn.btn-light..order__paginator--disabled "下一页"])]]) 

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI ORDER PRODUCT RETURN
(defn return-close-btn []
  [:div.order-edit__btn-group
   [:a.btn-light {:href "javascript:;"
                  :on-click #(do
                               (swap! app-state dissoc :panel)
                               (reset! return-state {})
                               (load-order-returns!))}
    "返回"]])

(defn return-tpe->text [tpe]
  (case tpe "return" "退货" "exchange" "换货"))


(defn return-panel []
  (fn []
    (let [{:keys [product ouid ts quantity tpe ship_id ship_provider status score price id]}
          @return-state]
      [:div.order-edit
       [return-close-btn]
       [:div.order-edit__fields
        [:div.order-edit__label "订单号"]
        [:div.order-edit__field ouid]
        [:div.order-edit__label "请求类型"]
        [:div.order-edit__field (return-tpe->text tpe)]
        [:div.order-edit__label "退换商品"]
        [:div.order-edit__field (get-in @return-state [:product :title])] 
        [:div.order-edit__label "快递"]
        [:div.order-edit__field (str ship_provider " / " ship_id)]
        [:div.order-edit__label "退货状态"]
        [:div.order-edit__field (return-status status)]
        [:div.order-edit__label "申请时间"]
        [:div.order-edit__field (ts->readable-time ts)]
        [:div.order-edit__label "退换数量"]
        [:div.order-edit__field quantity]
        [:div.order-edit__label "退还积分"]
        [:div.order-edit__field (str (* quantity (:score product 0)))]
        [:div.order-edit__label "退还现金"]
        [:div.order-edit__label (utils/to-fixed (/ (* quantity (:price product 0)) 100.0)) "元"]]
       (when (= "pending" (:status @return-state))
         [:div.order-edit__btn-group
          [:a.btn-light {:href "javascript:;"
                         :on-click #(when (js/confirm "确认同意退换请求并退款退积分？")
                                      (confirm-return! id))}
           "同意"]
          [:a.btn-light {:href "javascript:;"
                         :on-click #(when (js/confirm "确认拒绝此退换请求？")
                                      (reject-return! id))}
           "拒绝"]])])))

(defn return-list-panel []
  (fn []
    [:div.order
     [:div.order__query
      [:div "退换货状态"]
      [:select {:on-change #(let [idx (.. % -target -selectedIndex)
                                  status (-> (aget (.-target %) idx) .-value)]
                              (swap! app-state assoc :return-status status :return-page 1)
                              (load-order-returns!))
                :value (:return-status @app-state "all")}
       [:option {:value "all"} "所有"]
       (doall
        (for [[st txt] return-status]
          [:option {:value st :key (str "rtop." st)} txt]))]]
     [:div.order__list
      [:div.order__item.order__item--head.order__item--return
       [:div "订单号"]
       [:div "快递"]
       [:div "快递单号"]
       [:div "类型"]
       [:div "状态"]
       [:div "商品"]]
      (doall
       (for [{:keys [tpe id ouid ship_provider ship_id product quantity status] :as order} @order-return-list-store]
         [:div.order__item.clickable.order__item--return
          {:key (str "od." id)
           :on-click #(do
                        (reset! return-state order)
                        (swap! app-state assoc :panel :return-edit))}
          [:div ouid]
          [:div ship_provider]
          [:div ship_id]
          [:div (return-tpe->text tpe)]
          [:div (return-status status)]
          [:div (str (:title product) "x" quantity)]]))]
     [:div.order__paginator
      (if (> (:query-page @app-state) 1)
        [:a.btn.btn-light {:href "javascript:;" :on-click #(do
                                                             (swap! app-state update :return-page dec)
                                                             (load-orders!))}
         "上一页"]
        [:div.btn.btn-light.order__paginator--disabled "上一页"])
      [:span (:return-page @app-state)]
      (if (= (count @order-list-store) 20)
        [:a.btn.btn-light {:href "javascript:;" :on-click #(do
                                                             (swap! app-state update :return-page inc)
                                                             (load-orders!))}
         "下一页"]
        [:div.btn.btn-light..order__paginator--disabled "下一页"])]]))

(defn order-return-manage []
  (set-title! "退换货")
  (load-sellers!)
  (load-order-returns!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "商城"]
      "  >  "
      [:span.bkcrc-seceondT "订单发货"]]
     (case (:panel @app-state)
       :return-edit [return-panel]
       [return-list-panel])]))
 
