(ns ^:figwheel-always mok.pages.prod
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner
                      default-error-handler ts->readable-time
                      maybe-upload-file
                      make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?
                      load-sellers!]]
   [mok.states :refer [seller-list-store me]]
   
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

(def default-product {:score 0 :price-yuan 0 :sid -1 :status "show" :loc -1})

(defonce product-state (atom default-product))

(defonce product-list-store (atom []))

(defonce top-products-store (atom []))

(defn switch-to-panel [panel]
  (swap! app-state assoc :panel panel))

(defn remove-from-vec [vc el]
  (vec (remove (partial = el) vc)))

(defn add-to-vec [vc el]
  (-> vc
      (remove-from-vec el)
      (conj el)))

(defn load-top-prods! []
  (GET "/shop/top-product"
       {:response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! top-products-store (:data %))})
        :error-handler default-error-handler}))

(defn load-prods! []
  (GET "/shop/product"
       {:response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-fail "请求失败！"
                   :callback-success #(reset! product-list-store (:data %))})
        :error-handler default-error-handler}))

(defn invalid-product? [{:keys [description v-image price-yuan images cover title ts status id score v-tag v-cover tag price sid]}]
  (let [sid (if (admin?) sid (:sid @me))]
    (or
     (when (s/blank? description) {:key :description :msg "描述不能为空"})
     (when (not (seq v-image)) {:key :v-image :msg "至少上传一张内容图片"})
     (when (not (seq v-cover)) {:key :v-image :msg "至少上传一张封面图片"})
     (when (or (s/blank? price-yuan)
               (neg? price-yuan)) {:key :price-yuan :msg "价格不合法"})
     (when (or (s/blank? score)
               (neg? score)) {:key :score :msg "所需积分不合法"})
     (when (s/blank? title) {:key :title :msg "商品标题不能为空"})
     (when (not (pos? sid)) {:key :sid :msg "请选择卖家"}))))

(defn delete-product [{:keys [id]}]
  (DELETE "/shop/product"
       {:params {:pid id}
        :format :json
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-success "删除成功！" :msg-fail "请求失败！"
                   :callback-success #(do
                                        (load-prods!)
                                        (switch-to-panel :home)
                                        (reset! product-state default-product))})
        :error-handler default-error-handler}))

(defn add-product [prod]
  (PUT "/shop/product"
       {:params prod
        :format :json
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-success "添加成功！" :msg-fail "请求失败！"
                   :callback-success #(do
                                        (load-prods!)
                                        (switch-to-panel :home)
                                        (reset! product-state default-product))})
        :error-handler default-error-handler}))

(defn upsert-prod-btn-group [edit?]
  [:div.prod__btn-group
   [:a.btn-light {:href "javascript:;"
                  :on-click #(if-let [{:keys [key msg]} (invalid-product? @product-state)] 
                               (js/alert msg)
                               (add-product @product-state))}
    (if edit? "保存" "添加")]
   (when edit?
     [:a.btn-light {:href "javascript:;" :on-click #(delete-product @product-state)} "删除"])
   [:a.btn-light {:href "javascript:;" :on-click #(do
                                                    (swap! app-state dissoc :panel)
                                                    (reset! product-state default-product))} "取消"]])

(defn- cover-image-editor []
  [:div
   [:div.prod__cover-img {:title "封面图"}
    (doall
     (for [cover (:v-cover @product-state)]
       [:img
        {:key (str "cvr." cover)
         :src (if (s/includes? cover "/")
                cover
                (str "/broadcast/images/" cover))
         :on-click #(when (js/confirm "删除此图片？")
                      (swap! product-state update :v-cover remove-from-vec cover))}]))]
   [:input.prod__cover-btn
    {:type :file
     :on-change (fn [e]
                  (let [file (first (array-seq (.. e -target -files)))]
                    (maybe-upload-file
                     file
                     (fn [{:keys [data]}]
                       (swap! product-state update :v-cover add-to-vec data)))))}]])

(defn- content-image-editor []
  [:div
   [:div.prod__content-img {:title "详情图"}
    (doall
     (for [image (:v-image @product-state)]
       [:img
        {:key (str "cvr." image)
         :src (if (s/includes? image "/")
                image
                (str "/broadcast/images/" image))
         :on-click #(when (js/confirm "删除此图片？")
                      (swap! product-state update :v-image remove-from-vec image))}]))]
   [:input.prod__cover-btn
    {:type :file
     :on-change (fn [e]
                  (let [file (first (array-seq (.. e -target -files)))]
                    (maybe-upload-file
                     file
                     (fn [{:keys [data]}]
                       (swap! product-state update :v-image add-to-vec data)))))}]])

(defn- carousel-image-editor []
  [:div
   [:div.prod__cover-img {:title "轮播图"}
    (doall
     (for [image (:v-carousel @product-state)]
       [:img
        {:key (str "cvr." image)
         :src (if (s/includes? image "/")
                image
                (str "/broadcast/images/" image))
         :on-click #(when (js/confirm "删除此图片？")
                      (swap! product-state update :v-carousel remove-from-vec image))}]))]
   [:input.prod__cover-btn
    {:type :file
     :on-change (fn [e]
                  (let [file (first (array-seq (.. e -target -files)))]
                    (maybe-upload-file
                     file
                     (fn [{:keys [data]}]
                       (swap! product-state update :v-carousel add-to-vec data)))))}]])

(defn- thumbnail-image-editor []
  [:div
   [:div.prod__cover-img {:title "列表小图"}
    (doall
     (for [image (:v-thumbnail @product-state)]
       [:img
        {:key (str "cvr." image)
         :src (if (s/includes? image "/")
                image
                (str "/broadcast/images/" image))
         :on-click #(when (js/confirm "删除此图片？")
                      (swap! product-state update :v-thumbnail remove-from-vec image))}]))]
   [:input.prod__cover-btn
    {:type :file
     :on-change (fn [e]
                  (let [file (first (array-seq (.. e -target -files)))]
                    (maybe-upload-file
                     file
                     (fn [{:keys [data]}]
                       (swap! product-state update :v-thumbnail add-to-vec data)))))}]])

(defn product-panel []
  (let [tmp-tag (atom "")]
    (fn []
      (let [edit? (:id @product-state)
            status (:status @product-state)]
        [:div.prod
         [:h3 (if edit? "修改商品" "添加商品")
          [upsert-prod-btn-group edit?]]
         [:div.prod__details
          [:div.prod__label "店铺"]
          (if (admin?)
            [:select {:on-change #(let [idx (.. % -target -selectedIndex)
                                        sid (js/parseInt (-> (aget (.-target %) idx) .-value))]
                                    (swap! product-state assoc :sid sid))
                      :value (:sid @product-state -1)}
             [:option {:value -1} "请选择"]
             (doall
              (for [{:keys [id title]} @seller-list-store]
                [:option {:value id :key (str "sp." id)} title]))]
            (:title (first @seller-list-store)))
          [:div.prod__label "名称"]
          [:input {:type :text :value (:title @product-state "") :on-change #(swap! product-state assoc :title (-> % .-target .-value))}]
          [:div.prod__label "价格（元）"]
          [:input {:type :number :value (:price-yuan @product-state 0) :on-change #(swap! product-state assoc :price-yuan (-> % .-target .-value))}] 
          [:div.prod__label "积分"]
          [:input {:type :number :value (:score @product-state 0) :on-change #(swap! product-state assoc :score (-> % .-target .-value))}]
          [:div.prod__label "标签"]
          [:div.prod__tag-editor
           (doall
            (for [tag (:v-tag @product-state)]
              [:span.prod__tag-editor-tag
               {:key (str "tg." tag)
                :on-click #(swap! product-state update :v-tag remove-from-vec tag)}
               tag]))           
           [:input {:type :text :value @tmp-tag :on-change #(reset! tmp-tag (-> % .-target .-value))}]
           [:a.btn-light {:href "javascript:;"
                          :on-click #(when-not (s/blank? @tmp-tag)
                                       (if (s/includes? @tmp-tag ",")
                                         (make-toast :error "标签不能含有英文逗号：," )
                                         (do
                                           (swap! product-state update :v-tag add-to-vec @tmp-tag)
                                           (reset! tmp-tag ""))))}
            "添加"]]
          [:div.prod__label "介绍"]
          [:textarea {:value (:description @product-state "") :on-change #(let [desc (-> % .-target .-value)]
                                                                            (if (> (count desc) 30)
                                                                              (js/alert "描述必须在30个字符以内！")
                                                                              (swap! product-state assoc :description desc)))}]
          [:div.prod__label "封面图(宽高比：)"]
          [cover-image-editor]
          [:div.prod__label "轮播图(宽高比为2:1)"]
          [carousel-image-editor]
          [:div.prod__label "列表小图(宽高比为1:1)"]
          [thumbnail-image-editor]
          [:div.prod__label "内容图片(宽高比为4:3)"]
          [content-image-editor]
          [:div.prod__label "当前状态：" (if (= status "hide") "已下架" "销售中")]
          [:a.btn-light
           {:href "javascript:;" :on-click #(swap! product-state update :status (fn [st] (if (= status "hide") "show" "hide")))}
           (if (= status "hide") "点击上架" "点击下架")]
          (when (admin?)
            [:div.prod__label "置顶位置"])
          (when (admin?)
            [:div.prod__top-list
             (doall
              (for [i (range 1 11)]
                [:div.prod__top-item {:key (str "ti." i)
                                      :class (cond
                                               (= (:loc @product-state) i) "prod__top-item--active"
                                               ((set (map :loc @top-products-store)) i) "prod__top-item--taken")
                                      :on-click #(swap! product-state update :loc (fn [loc] (if (= loc i) -1 i)))}
                 (str i)]))])
          
          [upsert-prod-btn-group edit?]]]))))

(defn prod-list-panel []
  [:div.prod
   [:div.prod__list
    (doall
     (for [{:keys [id title description v-cover] :as prod} @product-list-store
           :let [first-cover (first v-cover)]]
       [:div.prod__item.clickable
        {:key (str "shop." id)
         :on-click #(do
                      (reset! product-state prod)
                      (swap! app-state assoc :panel :edit))}
        [:img {:src (if (s/includes? first-cover "/")
                      first-cover
                      (str "/broadcast/images/" first-cover))}]
        [:div title]]))]])

(defn prod-manage []
  (set-title! "商品管理")
  (load-sellers!)
  (load-prods!)
  (load-top-prods!)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "商城"]
      "  >  "
      [:span.bkcrc-seceondT "商品管理"]]
     (when (= (:panel @app-state :home) :home)
       [:button.buttons.mt10 {:on-click #(swap! app-state assoc :panel :add)} "添加"])
     (case (:panel @app-state)
       :add [product-panel]
       :edit [product-panel]
       [prod-list-panel])]))


