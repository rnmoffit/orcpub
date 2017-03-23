(ns orcpub.character-builder
  (:require [goog.dom :as gdom]
            [goog.labs.userAgent.device :as device]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as s]
            [clojure.set :as sets]

            [orcpub.common :as common]
            [orcpub.constants :as const]
            [orcpub.template :as t]
            [orcpub.entity :as entity]
            [orcpub.entity-spec :as es]
            [orcpub.dice :as dice]
            [orcpub.modifiers :as mod]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.modifiers :as mod5e]
            [orcpub.dnd.e5.options :as opt5e]
            [orcpub.dnd.e5.template :as t5e]
            [orcpub.dnd.e5.spells :as spells]
            [orcpub.dnd.e5.weapons :as weapon5e]
            [orcpub.dnd.e5.armor :as armor5e]
            [orcpub.dnd.e5.magic-items :as mi5e]
            [orcpub.dnd.e5.display :as disp5e]
            [orcpub.pdf-spec :as pdf-spec]

            [clojure.spec :as spec]
            [clojure.spec.test :as stest]

            [reagent.core :as r]))

(declare app-state)

(defn index-of-option [selection option-key]
  (first
   (keep-indexed
    (fn [i v]
      (if (= option-key (::entity/key v))
        i))
    selection)))

(defn get-option-value [template entity path]
  (get-in entity (entity/get-option-value-path template entity path)))

(defn update-in-entity [m [k & ks] f & args]
  (let [current (get m k)
         val (if (and (int? k)
                      (>= k (count current)))
               (vec (concat current (repeat (inc (- k (count current))))))
               current)]
     (if ks
       (assoc m k (apply update-in val ks f args))
       (assoc m k (apply f val args)))))

(defn update-option [template entity path update-fn]
  (update-in entity (entity/get-entity-path template entity path) update-fn))

(defn remove-list-option [template entity path index]
  (update-option template
                 entity
                 path
                 (fn [list]
                   (vec
                    (keep-indexed
                     (fn [i v] (if (not= i index) v))
                     list)))))

(def stored-char-str (.getItem js/window.localStorage "char-meta"))
(defn remove-stored-char [stored-char-str & [more-info]]
  (js/console.warn (str "Invalid char-meta: " stored-char-str more-info))
  (.removeItem js/window.localStorage "char-meta"))
(def stored-char (if stored-char-str (try (let [v (reader/read-string stored-char-str)]
                                            (if (spec/valid? ::entity/raw-entity v)
                                              v
                                              (remove-stored-char stored-char-str (str (spec/explain-data ::entity/raw-entity v)))))
                                          (catch js/Object
                                              e
                                            (remove-stored-char stored-char-str)))))

(def text-color
  {:color :white})

(def field-font-size
  {:font-size "14px"})

(defonce app-state
  (r/atom
   {:collapsed-paths #{[:ability-scores]
                       [:background]
                       [:race]
                       [:sources]
                       [:class :barbarian]}
    :expanded-paths {}
    :stepper-selection-path nil
    :stepper-selection nil
    :mouseover-option nil
    :builder {:character {:tab #{:build :options}}}
    :character (if stored-char stored-char t5e/character)}))

(def template (entity/sort-selections
               (t5e/template app-state)))

#_(add-watch app-state :log (fn [k r os ns]
                            (js/console.log "OLD" (clj->js os))
                              (js/console.log "NEW" (clj->js ns))))

(add-watch app-state
           :local-storage
           (fn [k r os ns]
             (.setItem js/window.localStorage "char-meta" (str (:character ns)))))

(declare builder-selector)

(defn dropdown-option [option]
  [:option.builder-dropdown-item
   {:value (str (::t/key option))}
   (::t/name option)])

(defn hide-mouseover-option! []
  (let [mouseover-option (js/document.getElementById "mouseover-option")]
    (if mouseover-option (set! (.-display (.-style mouseover-option)) "none"))))

(defn show-mouseover-option! []
  (let [mouseover-option (js/document.getElementById "mouseover-option")]
    (if mouseover-option (set! (.-display (.-style mouseover-option)) "block"))))

(defn set-mouseover-option! [opt]
  (show-mouseover-option!)
  (let [title-el (js/document.getElementById "mouseover-option-title")]
    (if title-el
      (do (set! (.-innerHTML title-el) (::t/name opt))
          (set! (.-innerHTML (js/document.getElementById "mouseover-option-help")) (or (::t/help opt) ""))))))

(defn dropdown [options selected-value change-fn built-char]
  [:select.builder-option.builder-option-dropdown
   {:on-change change-fn
    :value (or (str selected-value) "")
    :on-mouse-over (fn [_]
                     (if selected-value
                       (set-mouseover-option!
                        (first
                         (filter
                          #(= selected-value (::t/key %))
                          options)))))}
   [:option.builder-dropdown-item]
   (doall
    (map-indexed
     (fn [i option]
       ^{:key i} [dropdown-option option])
     (filter (fn [{:keys [::t/prereqs]}]
               (or (nil? prereqs)
                   (every? #(% built-char) prereqs)))
             options)))])

(defn set-option-value [char path value]
  (let [number-indices (keep-indexed (fn [i v] (if (number? v) i))
                                     path)
        subpaths (map #(subvec path 0 (inc %)) number-indices)]
    (assoc-in
     (reduce
      (fn [c p]
        (let [vec-path (butlast p)
              v (get-in c vec-path)
              remaining (inc (- (last p) (count v)))]
          (if (nil? v)
            (assoc-in c vec-path (vec (repeat remaining {})))
            c)))
      char
      subpaths)
     path
     value)))

(defn make-dropdown-change-fn [path key template raw-char app-state i]
  (fn [e]
    (let [new-path (concat path [key i])
          option-path (entity/get-entity-path template raw-char new-path)
          new-value (reader/read-string (.. e -target -value))]
      (swap! app-state #(update % :character (fn [c] (set-option-value c (conj option-path ::entity/key) new-value)))))))

(defn make-quantity-change-fn [path key template raw-char app-state i]
  (fn [e]
    (let [new-path (concat path [key i])
          option-path (entity/get-entity-path template raw-char new-path)
          raw-value (.. e -target -value)
          new-value (if (not (s/blank? raw-value)) (js/parseInt raw-value) 1)]
      (swap! app-state #(update % :character (fn [c] (set-option-value c (conj option-path ::entity/value) new-value)))))))

(defn to-option-path
  ([template-path template]
   (to-option-path template-path template []))
  ([template-path template current-option-path]
   (let [path-len (count template-path)
         key (::t/key (get-in template template-path))
         next-option-path (if key (conj current-option-path key) current-option-path)]
     (if (and key (> path-len 2))
       (recur (subvec template-path 0 (- path-len 2))
              template
              next-option-path)
       (vec (reverse next-option-path))))))

(defn option [path option-paths selectable? list-collapsed? {:keys [::t/key ::t/name ::t/selections ::t/modifiers ::t/prereqs ::t/ui-fn ::t/select-fn] :as opt} built-char raw-char changeable? options change-fn built-template collapsed-paths stepper-selection-path]
  (let [new-path (conj path key)
        selected? (boolean (get-in option-paths new-path))
        collapsed? (get collapsed-paths new-path)
        named-mods (filter ::mod/name modifiers)
        failed-prereqs (reduce
                        (fn [failures {:keys [::t/prereq-fn ::t/label]}]
                          (if (and prereq-fn (not (prereq-fn built-char)))
                            (conj failures label)))
                        []
                        prereqs)
        meets-prereqs? (empty? failed-prereqs)]
    ^{:key key}
    (if (or selected? (not list-collapsed?))
      [:div.builder-option
       {:class-name (clojure.string/join
                     " "
                     [(if selected? "selected-builder-option")
                      (if (and meets-prereqs? selectable?) "selectable-builder-option")
                      (if (not meets-prereqs?) "disabled-builder-option")])
        :on-click (fn [e]
                    (if (and meets-prereqs? selectable?)
                      (do
                        (if select-fn
                          (select-fn path))
                        (swap! app-state #(update % :character (fn [c] (update-option built-template c path (fn [o] (assoc o ::entity/key key))))))))
                    (.stopPropagation e))
        :on-mouse-enter (fn [e]
                         (let [stepper-selection-path stepper-selection-path
                               selection-path (to-option-path stepper-selection-path built-template)]
                           (set-mouseover-option! opt))
                        (.stopPropagation e))}
       [:div.option-header
        [:div.flex-grow-1
         (if changeable?
           [dropdown options key change-fn built-char]
           [:span.f-w-b name])
         (if (not meets-prereqs?)
           [:div.i.f-s-12.f-w-n 
            (str "Requires " (s/join ", " failed-prereqs))])
         (if (and meets-prereqs? (seq named-mods))
           [:span.m-l-10.i.f-s-12.f-w-n
            (s/join
             ", "
             (map
              (fn [{:keys [::mod/value ::mod/val-fn] :as m}]
                (let []
                  (str
                   (::mod/name m)
                   " "
                   (let [v (or value (get-option-value built-template (:character @app-state) path))]
                     (if val-fn
                       (val-fn v)
                       v)))))
              named-mods))])]
        (if (and selected?
                 (or (seq selections)
                     ui-fn))
          (if collapsed?
            [:div.flex
             {:on-click (fn [_]
                          (swap! app-state update :collapsed-paths disj new-path))}
             [:span.expand-collapse-button
              "Expand"]
             [:i.fa.fa-caret-down.m-l-5.orange.pointer]]
            [:div.flex
             {:on-click (fn [_]
                          (swap! app-state update :collapsed-paths conj new-path))}
             [:span.expand-collapse-button
              "Collapse"]
             [:i.fa.fa-caret-up.m-l-5.orange.pointer]]))]
       (if (and selected? (not collapsed?))
         [:div
          (if ui-fn (ui-fn path))
          [:div
           (doall
            (map
             (fn [{:keys [::t/prereq-fn ::t/key] :as selection}]
               (if (or (not prereq-fn) (prereq-fn built-char))
                 ^{:key key}
                 [builder-selector new-path option-paths selection built-char raw-char built-template collapsed-paths stepper-selection-path]))
             selections))]]
         (if (and (seq selections) collapsed?)
           [:div.builder-option.collapsed-list-builder-option]))])))

(def builder-selector-style)

(defn add-option-button [{:keys [::t/key ::t/name ::t/options ::t/new-item-fn ::t/new-item-text] :as selection} entity path built-template]
  [:div.orange.p-5.underline.pointer
   [:i.fa.fa-plus-circle.orange]
   [:span.m-l-5
    {:on-click
     (fn []
       (let [value-path (entity/get-entity-path built-template entity path)
             new-item (new-item-fn
                       selection
                       options
                       (get-in @app-state (concat [:character] value-path)))]
         (swap! app-state #(update % :character (fn [c] (update-option built-template c path
                                                                      (fn [options] (conj (vec options) new-item))))))))}
    (or new-item-text (str "Add " name))]])

(defn remove-option-button [path built-template index]
  [:i.fa.fa-minus-circle.remove-item-button.orange
   {:on-click
    (fn [e]
      (swap! app-state #(update % :character (fn [c] (remove-list-option built-template c path index)))))}])

(defn dropdown-selector [path option-paths {:keys [::t/options ::t/min ::t/max ::t/key ::t/name ::t/sequential? ::t/new-item-fn ::t/quantity?] :as selection} built-char raw-char built-template collapsed?]
  (if (not collapsed?)
    (let [change-fn (partial make-dropdown-change-fn path key built-template raw-char app-state)
          qty-change-fn (partial make-quantity-change-fn path key built-template raw-char app-state)
          options (filter (fn [{:keys [::t/prereq-fn]}]
                            (or (not prereq-fn) (prereq-fn built-char)))
                          options)]
      [:div
       (if max
         (if (= min max)
           (doall
            (for [i (range max)]
              (let [option-path (conj path key i)
                    entity-path (entity/get-entity-path built-template raw-char option-path)
                    key-path (conj entity-path ::entity/key)
                    value (get-in @app-state (concat [:character] key-path))]
                ^{:key i} [:div [dropdown options value (change-fn i) built-char]]))))
         [:div
          (let [full-path (conj path key)
                entity-path (entity/get-entity-path built-template raw-char full-path)
                selected (get-in @app-state (concat [:character] entity-path))
                remaining (- min (count selected))
                final-options (if (pos? remaining)
                                (vec (concat selected (repeat remaining {::entity/key nil})))
                                selected)]
            (doall
             (map-indexed
              (fn [i {value ::entity/key
                      qty-value ::entity/value
                      :as option}]
                ^{:key i}
                [:div.flex.align-items-c
                 [dropdown options value (change-fn i) built-char]
                 (if quantity?
                   [:input.input.m-l-5.w-70
                    {:type :number
                     :placeholder "QTY"
                     :value qty-value
                     :on-change (qty-change-fn i)}])
                 [remove-option-button full-path built-template i]])
              final-options)))
          (add-option-button selection raw-char (conj path key) built-template)])])
    [:div
     [:div.builder-option.collapsed-list-builder-option]
     [:div.builder-option.collapsed-list-builder-option]]))

(defn filter-selected [path key option-paths options raw-char built-template]
  (let [options-path (conj path key)
        entity-opt-path (entity/get-entity-path built-template raw-char options-path)
        selected (get-in raw-char entity-opt-path)]
    (if (sequential? selected)
      (let [options-map (into {} (map (juxt ::t/key identity) options))]
        (map
         (fn [{k ::entity/key}]
           (options-map k))
         selected))
      (filter
       (fn [opt]
         (let [option-path (concat path [key (::t/key opt)])]
           (get-in option-paths option-path)))
       options))))

(defn list-selector-option [removeable? path option-paths multiple-select? list-collapsed? i opt built-char raw-char changeable? options change-fn built-template collapsed-paths stepper-selection-path]
  [:div.list-selector-option
   [:div.flex-grow-1
    [option path option-paths (not multiple-select?) list-collapsed? opt built-char raw-char changeable? options change-fn built-template collapsed-paths stepper-selection-path]]
   (if (removeable? i)
     [remove-option-button path built-template i])])

(defn list-selector [path option-paths {:keys [::t/options ::t/min ::t/max ::t/key ::t/name ::t/sequential? ::t/new-item-fn] :as selection} collapsed? built-char raw-char built-template collapsed-paths stepper-selection-path]
  (let [no-max? (nil? max)
        multiple-select? (or no-max? (> max 1))
        selected-options (filter-selected path key option-paths options raw-char built-template)
        addable? (and multiple-select?
                      (or no-max?
                          (< (count selected-options) max)))
        more-than-min? (> (count selected-options) min)
        next-path (conj path key)]
    (assert (or (not multiple-select?)
                new-item-fn)
            (str "MULTIPLE SELECT LIST SELECTOR REQUIRES UI-FN! Offending selection: " next-path))
    [:div
     (if collapsed? [:div.builder-option.collapsed-list-builder-option])
     [:div
      (doall
       (map-indexed
        (fn [i option]
          ^{:key i}
          [list-selector-option
           #(and multiple-select?
                 more-than-min?
                 (or (not sequential?)
                     (= % (dec (count selected-options)))))
           next-path
           option-paths
           multiple-select?
           collapsed?
           i
           option
           built-char
           raw-char
           (and addable? (not sequential?))
           options
           (make-dropdown-change-fn path key built-template raw-char app-state i)
           built-template
           collapsed-paths
           stepper-selection-path])
        (if multiple-select?
          selected-options
          options)))]
     (if collapsed? [:div.builder-option.collapsed-list-builder-option])
     (if (and addable? new-item-fn)
       (add-option-button selection raw-char (conj path key) built-template))]))

(defn selector-id [path]
  (s/join "--" (map name path)))

(defn builder-selector [path option-paths {:keys [::t/name ::t/key ::t/min ::t/max ::t/ui-fn ::t/collapsible? ::t/options] :as selection} built-char raw-char built-template collapsed-paths stepper-selection-path]
  (let [new-path (conj path key)
        collapsed? (get collapsed-paths new-path)
        simple-options? 
        (or (::t/simple? selection)
            (not-any? #(or (seq (::t/selections %))
                           (some ::mod/name (::t/modifiers %))
                           (::t/ui-fn %))
                      options))
        collapsible? (or collapsible?
                         (and (not (or (nil? max) (> max min)))
                              (not simple-options?)))]
    ^{:key key}
    [:div.builder-selector
     {:id (selector-id new-path)}
     [:div.flex.justify-cont-s-b.align-items-c
      (if (zero? (count path))
        [:h1.f-s-24 (::t/name selection)]
        [:h2.builder-selector-header (::t/name selection)])
      (if collapsible?
        (if collapsed?
          [:div.flex
           {:on-click (fn [_]
                        (swap! app-state update :collapsed-paths disj new-path))}
           [:div.expand-collapse-button
            (if simple-options? "Expand" "Show All Options")]
           [:i.fa.fa-caret-down.m-l-5.orange.pointer]]
          [:div.flex
           {:on-click (fn [_]
                        (swap! app-state update :collapsed-paths conj new-path))}
           [:span.expand-collapse-button
            (if simple-options? "Collapse" "Hide Unselected Options")]
           [:i.fa.fa-caret-up.m-l-5.orange.pointer]]))]
     [:div
      (cond
        ui-fn (ui-fn selection)
        simple-options? [dropdown-selector path option-paths selection built-char raw-char built-template (and collapsible? collapsed?)]
        :else [list-selector path option-paths selection (and collapsible? collapsed?) built-char raw-char built-template collapsed-paths stepper-selection-path])]]))

(defn make-path-map-aux [character]
  (let [flat-options (entity/flatten-options (::entity/options character))]
    (reduce
     (fn [m v]
       (update-in m (::t/path v) (fn [c] (or c {}))))
     {}
     flat-options)))

(def memoized-make-path-map-aux (memoize make-path-map-aux))

(defn make-path-map [character]
  (memoized-make-path-map-aux character))

(defn abilities-radar [size abilities ability-bonuses]
  (let [d size
        stroke 1.5
        point-offset 10
        double-point-offset (* point-offset 2)
        double-stroke (* 2 stroke)
        alpha (/ d 4)
        triple-alpha (* alpha 3)
        beta (* alpha (Math/sqrt 3))
        double-beta (* beta 2)
        points [[0 (- (* alpha 2))]
                [beta (- alpha)]
                [beta alpha]
                [0 (* alpha 2)]
                [(- beta) alpha]
                [(- beta) (- alpha)]]
        offset-ability-keys (take 6 (drop 1 (cycle char5e/ability-keys)))
        text-points [[0 55] [106 0] [206 55] [206 190] [106 240] [0 190]]
        abilities-points (map
                          (fn [k [x y]]
                            (let [ratio (double (/ (k abilities) 20))]
                              [(* x ratio) (* y ratio)]))
                          offset-ability-keys
                          points)
        color-names (map-indexed
                     (fn [i c]
                       {:key i
                        :color c})
                     ["f4692a" "f32e50" "b35c95" "47eaf8" "bbe289" "f9b747"])
        colors (map
                (fn [{:keys [key color]}]
                  {:key key
                   :color (str "#" color)})
                color-names)]
    [:div.posn-rel.w-100-p
     (doall
      (map
       (fn [k [x y] {:keys [color]}]
         (let [color-class (str "c-" color)]
           ^{:key color}
           [:div.t-a-c.w-50.posn-abs {:style {:left x :top y}}
            [:div
             [:span (s/upper-case (name k))]
             [:span.m-l-5 {:class-name color-class} (k abilities)]]
            [:div {:class-name color-class}
             (let [bonus (int (/ (- (k abilities) 10) 2))]
               (str "(" (mod/bonus-str (k ability-bonuses)) ")"))]]))
       offset-ability-keys
       (take 6 (drop 1 (cycle text-points)))
       color-names))
     [:svg {:width 220 :height (+ 60 d double-point-offset)}
      [:defs
       (doall
        (map
         (fn [[x1 y1] [x2 y2] c1 c2]
           ^{:key (:key c1)}
           [:g
            [:linearGradient {:id (str "lg-" (:key c1) "-o")
                              :x1 x1 :y1 y1 :x2 0 :y2 0
                              :gradientUnits :userSpaceOnUse}
             [:stop {:offset "0%" :stop-color (:color c1)}]
             [:stop {:offset "70%" :stop-color (:color c2) :stop-opacity 0}]]
            [:linearGradient {:id (str "lg-" (:key c1) "-" (:key c2))
                              :x1 x1 :y1 y1 :x2 x2 :y2 y2
                              :gradientUnits :userSpaceOnUse}
             [:stop {:offset "0%" :stop-color (:color c1)}]
             [:stop {:offset "100%" :stop-color (:color c2)}]]])
         points
         (drop 1 (cycle points))
         colors
         (drop 1 (cycle colors))))]
      [:g {:transform (str "translate(" (+ 40 beta point-offset) "," (+ 30 (* alpha 2) point-offset) ")")}
       [:polygon.abilities-polygon
        {:stroke "#31bef8"
         :fill "rgba(48, 189, 248, 0.2)"
         :points (s/join " " (map (partial s/join ",") abilities-points))}]
       (doall
        (map
         (fn [[x1 y1] [x2 y2] c1 c2]
           ^{:key (:key c1)}
           [:g
            [:line {:x1 x1 :y1 y1 :x2 0 :y2 0 :stroke (str "url(#lg-" (:key c1) "-o)")}]
            [:line {:x1 x1 :y1 y1 :x2 x2 :y2 y2 :stroke (str "url(#lg-" (:key c1) "-" (:key c2) ")") :stroke-width 1.5}]
            [:circle {:cx (* x1 1.05) :cy (* y1 1.05) :r 1 :fill (:color c1)}]])
         points
         (drop 1 (cycle points))
         colors
         (drop 1 (cycle colors))))]]]))

;;(stest/instrument `entity/build)
;;(stest/instrument `t/make-modifier-map)

;;(prn "MODIFIER MAP" (cljs.pprint/pprint (t/make-modifier-map template)))
;;(spec/explain ::t/modifier-map (t/make-modifier-map template))

(defn realize-char [built-char]
  (reduce-kv
   (fn [m k v]
     (let [realized-value (es/entity-val built-char k)]
       (if (fn? realized-value)
         m
         (assoc m k realized-value))))
   (sorted-map)
   built-char))

(defn print-char [built-char]
  (cljs.pprint/pprint
   (realize-char built-char)))

(defn svg-icon [icon-name]
  [:img.h-32.w-32 {:src (str "image/" icon-name ".svg")}])

(defn display-section [title icon-name value & [list?]]
  [:div.m-t-20
   [:div.flex.align-items-c
    (if icon-name (svg-icon icon-name))
    [:span.f-s-16.f-w-600.m-l-5 title]]
   [:div {:class-name (if list? "m-t-0" "m-t-4")}
    [:span.f-s-24.f-w-600
     value]]])

(defn list-display-section [title image-name values]
  (if (seq values)
    (display-section
     title
     image-name
     [:span.m-t-5.f-s-14.f-w-n.i
      (s/join
       ", "
       values)]
     true)))

(defn svg-icon-section [title icon-name content]
  [:div.m-t-20
   [:span.f-s-16.f-w-600 title]
   [:div.flex.align-items-c
    (svg-icon icon-name)
    [:div.f-s-24.m-l-10.f-w-b content]]])

(defn armor-class-section [armor-class armor-class-with-armor equipped-armor]
  (let [equipped-armor-full (map (comp mi5e/all-armor-map first) equipped-armor)
        shields (filter #(= :shield (:type %)) equipped-armor-full)
        armor (filter #(not= :shield (:type %)) equipped-armor-full)
        display-rows (for [a (conj armor nil)
                           shield (conj shields nil)]
                       (let [el (if (= nil a shield) :span :div)]
                         ^{:key (common/name-to-kw (str (:name a) (:name shield)))}
                        [el
                         [el
                          [:span.f-s-24.f-w-b (armor-class-with-armor a shield)]
                          [:span.display-section-qualifier-text (str "("
                                                                     (if a (:name a) "unarmored")
                                                                     (if shield (str " & " (:name shield)))
                                                                     ")")]]]))]
    (svg-icon-section
     "Armor Class"
     "checked-shield"
     [:span
       (first display-rows)
       [:div
        (doall (rest display-rows))]])))

(defn speed-section [built-char]
  (let [speed (char5e/base-land-speed built-char)
        speed-with-armor (char5e/land-speed-with-armor built-char)
        unarmored-speed-bonus (char5e/unarmored-speed-bonus built-char)
        equipped-armor (char5e/normal-armor-inventory built-char)]
    [svg-icon-section
     "Speed"
     "walking-boot"
     [:span
      [:span
       [:span (+ (or unarmored-speed-bonus 0)
                 (if speed-with-armor
                   (speed-with-armor nil)
                   speed))]
       (if (or unarmored-speed-bonus
               speed-with-armor)
         [:span.display-section-qualifier-text "(unarmored)"])]
      (if speed-with-armor
        [:div
         (doall
          (map
           (fn [[armor-kw _]]
             (let [armor (mi5e/all-armor-map armor-kw)
                   speed (speed-with-armor armor)]
               ^{:key armor-kw}
               [:div
                [:div
                 [:span speed]
                 [:span.display-section-qualifier-text (str "(" (:name armor) " armor)")]]]))
           (dissoc equipped-armor :shield)))]
        (if unarmored-speed-bonus
          [:div
           [:span
            [:span speed]
            [:span.display-section-qualifier-text "(armored)"]]]))]
     (let [swim-speed (char5e/base-swimming-speed built-char)]
       (if swim-speed
         [:div [:span swim-speed] [:span.display-section-qualifier-text "(swim)"]]))]))

(defn list-item-section [list-name icon-name items & [name-fn]]
  [list-display-section list-name icon-name
   (map
    (fn [item]
      ((or name-fn :name) item))
    items)])

(defn compare-spell [spell-1 spell-2]
  (let [key-fn (juxt :key :ability)]
    (compare (key-fn spell-1) (key-fn spell-2))))

(defn spells-known-section [spells-known]
  [display-section "Spells Known" "spell-book"
   [:div.f-s-14
    (doall
     (map
      (fn [[level spells]]
        ^{:key level}
        [:div.m-t-10
         [:span.f-w-600 (if (zero? level) "Cantrip" (str "Level " level))]
         [:div.i.f-w-n
          (doall
           (map-indexed
            (fn [i spell]
              (let [spell-data (spells/spell-map (:key spell))]
                ^{:key i}
                [:div
                 (str
                  (:name (spells/spell-map (:key spell)))
                  " ("
                  (s/join
                   ", "
                   (remove
                    nil?
                    [(if (:ability spell) (s/upper-case (name (:ability spell))))
                     (if (:qualifier spell) (:qualifier spell))]))
                
                  ")")]))
            (into
             (sorted-set-by compare-spell)
             (filter
              (fn [{k :key}]
                (spells/spell-map k))
              spells))))]])
      spells-known))]])

(defn equipment-section [title icon-name equipment equipment-map]
  [list-display-section title icon-name
   (map
    (fn [[equipment-kw num]]
      (str (:name (equipment-map equipment-kw)) " (" num ")"))
    equipment)])

(defn attacks-section [attacks]
  (if (seq attacks)
    (display-section
     "Attacks"
     "pointy-sword"
     [:div.f-s-14
      (doall
       (map
        (fn [{:keys [name area-type description damage-die damage-die-count damage-type save save-dc] :as attack}]
          ^{:key name}
          [:p.m-t-10
           [:span.f-w-600.i name "."]
           [:span.f-w-n.m-l-10 (common/sentensize (disp5e/attack-description attack))]])
        attacks))])))

(defn actions-section [title icon-name actions]
  (if (seq actions)
    (display-section
     title icon-name
     [:div.f-s-14
      (doall
       (map
        (fn [action]
          ^{:key action}
          [:p.m-t-10
           [:span.f-w-600.i (:name action) "."]
           [:span.f-w-n.m-l-10 (common/sentensize (disp5e/action-description action))]])
        actions))])))

(defn prof-name [prof-map prof-kw]
  (or (-> prof-kw prof-map :name) (common/safe-name prof-kw)))

(defn character-display [built-char]
  (let [race (char5e/race built-char)
        subrace (char5e/subrace built-char)
        classes (char5e/classes built-char)
        levels (char5e/levels built-char)
        darkvision (char5e/darkvision built-char)
        skill-profs (char5e/skill-proficiencies built-char)
        tool-profs (char5e/tool-proficiencies built-char)
        weapon-profs (char5e/weapon-proficiencies built-char)
        armor-profs (char5e/armor-proficiencies built-char)
        resistances (char5e/damage-resistances built-char)
        immunities (char5e/damage-immunities built-char)
        condition-immunities (char5e/condition-immunities built-char)
        languages (char5e/languages built-char)
        abilities (char5e/ability-values built-char)
        ability-bonuses (char5e/ability-bonuses built-char)
        armor-class (char5e/base-armor-class built-char)
        armor-class-with-armor (char5e/armor-class-with-armor built-char)
        armor (char5e/normal-armor-inventory built-char)
        magic-armor (char5e/magic-armor-inventory built-char)
        spells-known (char5e/spells-known built-char)
        weapons (char5e/normal-weapons-inventory built-char)
        magic-weapons (char5e/magic-weapons-inventory built-char)
        equipment (char5e/normal-equipment-inventory built-char)
        magic-items (char5e/magical-equipment-inventory built-char)
        traits (char5e/traits built-char)
        attacks (char5e/attacks built-char)
        bonus-actions (char5e/bonus-actions built-char)
        reactions (char5e/reactions built-char)
        actions (char5e/actions built-char)]
    [:div
     [:div.f-s-24.f-w-600.m-b-16.text-shadow
      [:span race]
      (if (seq levels)
        [:span.m-l-10
         (apply
          str
          (interpose
           " / "
           (map
            (fn [cls-key]
              (let [{:keys [class-name class-level subclass]} (levels cls-key)]
                (str class-name " (" class-level ")")))
            classes)))])]
     [:div.details-columns
      [:div.flex-grow-1.flex-basis-50-p
       [:div.w-100-p.t-a-c
        [:div.flex.justify-cont-s-b.p-10
         (doall
          (map
           (fn [k]
             ^{:key k}
             [:div
              (t5e/ability-icon k 32)
              [:div.f-s-20.uppercase (name k)]
              [:div.f-s-24.f-w-b (abilities k)]
              [:div.f-s-12.opacity-5.m-b--2.m-t-2 "mod"]
              [:div.f-s-18 (common/bonus-str (ability-bonuses k))]])
           char5e/ability-keys))]
        #_[abilities-radar 187 (char5e/ability-values built-char) ability-bonuses]]
       [:div.flex
        [:div.w-50-p
         [:img.character-image.w-100-p.m-b-20 {:src (or (get-in @app-state [:character ::entity/values :image-url]) "image/barbarian-girl.png")}]]
        [:div.w-50-p
         [armor-class-section armor-class armor-class-with-armor (merge magic-armor armor)]
         [svg-icon-section "Hit Points" "health-normal" (char5e/max-hit-points built-char)]
         [speed-section built-char]
         #_[display-section "Speed" nil
          (let [unarmored-speed-bonus (char5e/unarmored-speed-bonus built-char)
                speed (char5e/base-land-speed built-char)
                swim-speed (char5e/base-swimming-speed built-char)
                speed-with-armor (char5e/speed-with-armor built-char)]
            [:div
             [:div
              (if speed-with-armor
                (speed-section speed-with-armor armor)
                speed)]
             (if swim-speed
               [:div [:span swim-speed] [:span.display-section-qualifier-text "(swim)"]])])]
         [svg-icon-section "Darkvision" "night-vision" (if darkvision (str darkvision " ft.") "--")]
         [svg-icon-section "Initiative" "sprint" (mod/bonus-str (char5e/initiative built-char))]
         [display-section "Proficiency Bonus" nil (mod/bonus-str (char5e/proficiency-bonus built-char))]
         [display-section "Passive Perception" nil (char5e/passive-perception built-char)]
         (let [num-attacks (char5e/number-of-attacks built-char)]
           (if (> num-attacks 1)
             [display-section "Number of Attacks" nil num-attacks]))
         (let [criticals (char5e/critical-hit-values built-char)
               min-crit (apply min criticals)
               max-crit (apply max criticals)]
           (if (not= min-crit max-crit)
             (display-section "Critical Hit" nil (str min-crit "-" max-crit))))
         [:div
          [list-display-section
           "Save Proficiencies" nil
           (map (comp s/upper-case name) (char5e/saving-throws built-char))]
          (let [save-advantage (char5e/saving-throw-advantages built-char)]
            [:ul.list-style-disc.m-t-5
             (doall
              (map-indexed
               (fn [i {:keys [abilities types]}]
                 ^{:key i}
                 [:li (str "advantage on "
                           (common/list-print (map (comp s/lower-case :name opt5e/abilities-map) abilities))
                           " saves against "
                           (common/list-print
                            (map #(let [condition (opt5e/conditions-map %)]
                                    (cond
                                      condition (str "being " (s/lower-case (:name condition)))
                                      (keyword? %) (name %)
                                      :else %))
                                 types)))])
               save-advantage))])]]]]
      [:div.flex-grow-1.flex-basis-50-p
       [list-display-section "Skill Proficiencies" "juggler"
        (let [skill-bonuses (char5e/skill-bonuses built-char)]
          (map
           (fn [[skill-kw bonus]]
             (str (s/capitalize (name skill-kw)) " " (mod/bonus-str bonus)))
           (filter (fn [[k bonus]]
                     (not= bonus (ability-bonuses (:ability (opt5e/skills-map k)))))
                   skill-bonuses)))]
       [list-item-section "Languages" "public-speaker" languages (partial prof-name opt5e/language-map)]
       [list-item-section "Tool Proficiencies" "stone-crafting" tool-profs (partial prof-name opt5e/tools-map)]
       [list-item-section "Weapon Proficiencies" "bowman" weapon-profs (partial prof-name weapon5e/weapons-map)]
       [list-item-section "Armor Proficiencies" "mailed-fist" armor-profs (partial prof-name armor5e/armor-map)]
       [list-item-section "Damage Resistances" "surrounded-shield" resistances name]
       [list-item-section "Damage Immunities" nil immunities name]
       [list-item-section "Condition Immunities" nil condition-immunities (fn [{:keys [condition qualifier]}]
                                                                        (str (name condition)
                                                                             (if qualifier (str " (" qualifier ")"))))]
       (if (seq spells-known) [spells-known-section spells-known])
       [equipment-section "Weapons" "plain-dagger" (concat magic-weapons weapons) mi5e/all-weapons-map]
       [equipment-section "Armor" "breastplate" (merge magic-armor armor) mi5e/all-armor-map]
       [equipment-section "Equipment" "backpack" (concat magic-items equipment) mi5e/all-equipment-map]
       [attacks-section attacks]
       [actions-section "Bonus Actions" "jump-across" bonus-actions]
       [actions-section "Reactions" "dodging" reactions]
       [actions-section "Actions" "run" actions]
       [actions-section "Features, Traits, and Feats" "vitruvian-man" traits]]]]))

(def tab-path [:builder :character :tab])

(def plugins
  [{:name "Sword Coast Adventurer's Guide"
    :key :sword-coast-adventurers-guide
    :selections (t5e/sword-coast-adventurers-guide-selections (:character @app-state))}
   {:name "Volo's Guide to Monsters"
    :key :volos-guide-to-monsters
    :selections (t5e/volos-guide-to-monsters-selections (:character @app-state))}])

(def plugins-map
  (zipmap (map :key plugins) plugins))

(def option-sources-selection
  {::t/name "Option Sources"
   ::t/optional? true
   ::t/help "Select the sources you want to use for races, classes, etc. Click the 'Show All Options' button to make additional selections. If you are new to the game we recommend just moving on to the next step."})

(defn get-all-selections-aux [path {:keys [::t/key ::t/selections ::t/options] :as obj} parent selected-option-paths]
  (let [children (map
                  (fn [{:keys [::t/key] :as s}]
                    (get-all-selections-aux (conj path key) s obj selected-option-paths))
                  (or selections options))]
    (cond
      selections
      (if (get-in selected-option-paths path) children)
      
      options
      (if key
        (concat
         [(assoc obj ::path path ::parent parent)]
         children)
        children))))

(defn get-all-selections [path obj selected-option-paths built-char]
  (remove #(or (nil? %)
               (let [prereq-fn (::t/prereq-fn %)]
                 (and prereq-fn (not (prereq-fn built-char)))))
          (flatten (get-all-selections-aux path obj nil selected-option-paths))))

(defn selection-made? [built-template selected-option-paths character selection]
  (let [option (get-in selected-option-paths (::path selection))
        entity-path (entity/get-entity-path built-template character (::path selection))
        selections (get-in character entity-path)
        min-count (::t/min selection)]
    (and option (or (= min-count 1) (and (vector? selections) (>= (count (filter ::entity/key selections)) min-count))))))

(defn drop-selected [built-template selected-option-paths character selections]
  (remove
   (partial selection-made? built-template selected-option-paths character)
   selections))

(defn selection-after [current-path all-selections]
  (let [up-to-current (drop-while
                       (fn [s]
                         (not= (::path s) current-path))
                       all-selections)
        up-to-next (drop 1 up-to-current)
        next (first up-to-next)]
    (if next
      [(::path next) next]
      (let [first-selection (first all-selections)]
        [(::path first-selection) first-selection]))))

(defn next-selection [current-template-path built-template selected-option-paths character built-char]
  (let [current-path (to-option-path current-template-path built-template)
        all-selections (get-all-selections [] built-template selected-option-paths built-char)]
    (selection-after current-path all-selections)))

(defn prev-selection [current-template-path built-template selected-option-paths character built-char]
  (let [current-path (to-option-path current-template-path built-template)
        all-selections (get-all-selections [] built-template selected-option-paths built-char)
        up-to-current (take-while
                       (fn [s]
                         (not= (::path s) current-path))
                       all-selections)
        prev (last up-to-current)]
    (if prev
      [(::path prev) prev]
      (let [last-selection (last all-selections)]
        [(::path last-selection) last-selection]))))

(defn collapse-paths [state paths]
  (let [all-paths (mapcat #(reductions conj [] %) paths)]
    (reduce
     (fn [s path]
       (update s :collapsed-paths conj path))
     state
     all-paths)))

(defn open-path-and-subpaths [state path]
  (reduce
   (fn [s subpath]
     (update s :collapsed-paths disj subpath))
   state
   (reductions conj [] path)))

(defn set-stepper-top! [top]
  (let [stepper-element (js/document.getElementById "selection-stepper")]
    (set! (.-top (.-style stepper-element)) (str top "px"))))

(defn to-selection-path [entity-path entity]
  (vec
   (remove
    nil?
    (map (fn [path]
           (let [last-key (last path)]
             (cond
               (= last-key ::entity/options) nil
               (number? last-key) (::entity/key (get-in entity path))
               :else last-key)))
         (reductions conj [] entity-path)))))

(defn set-next-template-path! [built-template next-path next-template-path next-selection character]
  (swap!
   app-state
   (fn [as]
     (-> as
         (assoc :stepper-selection-path next-template-path)
         (assoc :stepper-selection next-selection)))))

(defn set-next-selection! [built-template option-paths character stepper-selection-path all-selections built-char]
  (let [[next-path {:keys [::t/name] :as next-selection}]
        (if (nil? stepper-selection-path)
          (let [s (second all-selections)]
            [(::path s) s])
          (next-selection
           stepper-selection-path
           built-template
           option-paths
           character
           built-char))
        next-template-path (if next-path (entity/get-template-selection-path built-template next-path []))]
    (set-next-template-path! built-template next-path next-template-path next-selection character)))

(defn set-prev-selection! [built-template option-paths character stepper-selection-path all-selections built-char]
  (let [[prev-path {:keys [::t/name] :as next-selection}]
        (if (nil? stepper-selection-path)
          (let [s (last all-selections)]
            [(::path s) s])
          (prev-selection
           stepper-selection-path
           built-template
           option-paths
           character
           built-char))
        prev-template-path (if prev-path (entity/get-template-selection-path built-template prev-path []))]
    (set-next-template-path! built-template prev-path prev-template-path next-selection character)))

(defn level-up! [built-template character]
  (let [entity-path [::entity/options :class 0 ::entity/options :levels]
        lvls (get-in @app-state (concat [:character] entity-path))
        next-lvl (-> lvls count inc str keyword)
        selection-path (to-selection-path entity-path (:character @app-state))
        next-lvl-path (conj selection-path next-lvl)
        template-path (entity/get-template-selection-path built-template next-lvl-path [])
        option (get-in built-template template-path)
        first-selection (first (::t/selections option))
        selection-key (::t/key first-selection)
        next-path (conj next-lvl-path selection-key)
        next-template-path (conj template-path ::t/selections 0)]
    (swap!
     app-state
     update-in
     (concat [:character] entity-path)
     conj
     {::entity/key next-lvl})
    (set-next-template-path! built-template next-path next-template-path nil character)))

#_(defn selection-stepper [built-template option-paths character stepper-selection-path]
  (let [selection (if stepper-selection-path (get-in built-template stepper-selection-path))
        all-selections (get-all-selections [] built-template option-paths)
        unselected-selections (drop-selected built-template option-paths character all-selections)
        unselected-selections? (pos? (count unselected-selections))
        level-up? (not (or selection unselected-selections?))
        complete? (not unselected-selections?)]
    [:div.flex.selection-stepper-inner
     {:id "selection-stepper"}
     [:div.selection-stepper-main
      [:h1.f-w-bold.selection-stepper-title "Step-By-Step"]
      (if selection
        [:div
         [:h1.f-w-bold.m-t-10 "Step: " (::t/name selection)
          (if (::t/optional? selection)
            [:span.m-l-5.f-s-10 "(optional)"])]
         (let [help (::t/help selection)
               help-vec (if (vector? help) help [help])]
           (if (string? help)
             [:p.m-t-5.selection-stepper-help help]
             help))
         [:div#mouseover-option.b-1.b-rad-5.b-color-gray.p-10.m-t-10.hidden
          [:span#mouseover-option-title.f-w-b]
          [:p#mouseover-option-help]]])
      (if (or (not selection) level-up? complete?)
        [:div.m-t-10
         (if unselected-selections?
           "Click 'Get Started' to step through the build process."
           (str "All selections complete. Click "
                (if level-up?
                  "'Level Up' to your advance character level"
                  "'Finish' to complete")
                " or 'Dismiss' to hide this guide."))])
      [:div.flex.m-t-10.selection-stepper-footer
       [:span.link-button.m-r-5
        {:on-click (fn [_]
                     (swap! app-state assoc :stepper-dismissed true))}
        "Dismiss"]
       [:button.form-button.selection-stepper-button
        {:on-click
         (fn [_] (if level-up?
                   (level-up! built-template character)
                   (set-next-selection! built-template option-paths character stepper-selection-path unselected-selections)))}
        (cond
          level-up? "Level Up"
          complete? "Finish"
          selection "Next Step"
          unselected-selections? "Get Started")]]]
     (if selection
       [:svg.m-l--1.m-t-10 {:width "20" :height "24"}
        [:path 
         {:d "M-2 1.5 L13 14 L-2 22.5"
          :stroke :white
          :fill "#1a1e28"
          :stroke-width "1px"}]])]))

(defn option-sources [collapsed-paths selected-plugins]
  (let [path [:sources]
         collapsed? (collapsed-paths path)]
     [:div.w-100-p
      [:div.flex.justify-cont-s-b.align-items-c.m-b-5
       [:h1.f-s-16 "Option Sources"]
       (if collapsed?
         [:div
          {:on-click (fn [_]
                       (swap! app-state update :collapsed-paths disj path))}
          [:span.expand-collapse-button
           "Show All Options"]
          [:i.fa.fa-caret-down.m-l-5.orange.pointer]]
         [:div.flex
          {:on-click (fn [_]
                       (swap! app-state update :collapsed-paths conj path))}
          [:span.expand-collapse-button
           "Hide Unselected Options"]
          [:i.fa.fa-caret-up.m-l-5.orange.pointer]])]
      [:div.b-1.b-rad-5.p-10
       (if collapsed?
         [:span (s/join ", " (conj (map :name (filter #((:key %) selected-plugins) plugins)) "Player's Handbook"))]
         [:div
          [:div.checkbox-parent
           [:span.checkbox.checked.disabled
            [:i.fa.fa-check.orange]]
           [:span.checkbox-text "Player's Handbook"]]
          (doall
           (map
            (fn [{:keys [name key]}]
              (let [checked? (and selected-plugins (selected-plugins key))]
                ^{:key key}
                [:div.checkbox-parent
                 {:on-click (fn [_] (swap! app-state assoc-in [:plugins key] (not checked?)))}
                 [:span.checkbox
                  {:class-name (if checked? "checked")}
                  (if checked? [:i.fa.fa-check.orange])]
                 [:span.checkbox-text.pointer name]]))
            plugins))])]]))

(defn get-template-from-props [x]
  (get (.-argv (.-props x)) 6))


(defn on-builder-selector-update [x & args]
  (let [built-template ((vec (first args)) 6)
        stepper-selection-path (get @app-state :stepper-selection-path)
        selection (get-in built-template stepper-selection-path)
        selection-path (to-option-path stepper-selection-path built-template)
        selection-id (selector-id selection-path)
        element (js/document.getElementById selection-id)
        top (if element (.-offsetTop element) 0)
        stepper-element (js/document.getElementById "selection-stepper")
        options-top (.-offsetTop (js/document.getElementById "options-column"))]
    (if (pos? top) (set-stepper-top! (- top options-top)))))

(def builder-selector-component
  (with-meta
    builder-selector
    {:component-did-update on-builder-selector-update}))

(defn help-section [help]
  [:div.m-t-5.f-w-n
   (if (string? help)
     (doall
      (map-indexed
       (fn [i para]
         ^{:key i}
         [:p.m-b-5.m-b-0-last para])
       (s/split help #"\n")))
     help)])

(defn expand-button [option-path expand-text collapse-text & [handler]]
  (let [full-path [:expanded-paths option-path]
        expanded? (get-in @app-state full-path)]
    [:span.flex.pointer
     {:on-click (fn [e]
                  (swap! app-state update-in full-path not)
                  (if handler (handler e))
                  (.stopPropagation e))}
     [:span.underline.orange.p-0.m-r-2 (if expanded? expand-text collapse-text)]
     [:i.fa.orange
      {:class-name (if expanded? "fa-angle-up" "fa-angle-down")}]]))

(defn show-info-button [expanded? option-path]
  [:span.f-w-n.m-l-5 (expand-button option-path "hide info" "show info")])

(defn set-next! [char next-selection next-selection-path]
  (swap! app-state
         (fn [as]
           (cond-> as
             char (assoc :character char)
             next-selection-path (assoc :stepper-selection-path next-selection-path)
             next-selection (assoc :stepper-selection next-selection)))))

(defn option-selector [character built-char built-template option-paths stepper-selection-path option-path
                       {:keys [::t/min ::t/max ::t/options] :as selection}
                       disable-select-new?
                       {:keys [::t/key ::t/name ::t/path ::t/help ::t/selections ::t/prereqs ::t/select-fn ::t/ui-fn]}]
  (let [new-option-path (conj option-path key)
        selected? (get-in option-paths new-option-path)
        failed-prereqs (reduce
                        (fn [failures {:keys [::t/prereq-fn ::t/label]}]
                          (if (and prereq-fn (not (prereq-fn built-char)))
                            (conj failures label)))
                        []
                        prereqs)
        meets-prereqs? (empty? failed-prereqs)
        selectable? (and meets-prereqs?
                         (or (not disable-select-new?)
                             selected?))
        expanded? (get-in @app-state [:expanded-paths new-option-path])
        has-selections? (seq selections)]
    ^{:key key}
    [:div.p-10.b-1.b-rad-5.m-5.b-orange.hover-shadow
     {:class-name (s/join " " (remove nil? [(if selected? "b-w-5")
                                            (if selectable? "pointer")
                                            (if (not selectable?) "opacity-5")]))
      :on-click (fn [e]
                  (when (and (or (> max 1)
                                 (nil? max)
                                 (not selected?)
                                 has-selections?)
                             meets-prereqs?
                             selectable?)
                    (let [updated-char (let [new-option {::entity/key key}]
                                         (if (or
                                              (::t/multiselect? selection)
                                              (and
                                               (> min 1)
                                               (= min max)))
                                           (update-option
                                            built-template
                                            character
                                            option-path
                                            (fn [parent-vec]
                                              (if (nil? parent-vec)
                                                [new-option]
                                                (let [parent-keys (into #{} (map ::entity/key) parent-vec)]
                                                  (if (parent-keys key)
                                                    (vec (remove #(= key (::entity/key %)) parent-vec))
                                                    (conj parent-vec new-option))))))
                                           (update-option
                                            built-template
                                            character
                                            new-option-path
                                            (fn [o] (assoc o ::entity/key key)))))
                          next-option-paths (make-path-map updated-char)
                          next-all-selections (get-all-selections [] built-template next-option-paths built-char)
                          [next-selection-path next-selection] (selection-after option-path next-all-selections)
                          next-template-path (entity/get-template-selection-path built-template next-selection-path [])]
                      (set-next! updated-char
                                 (if has-selections? next-selection)
                                 (if has-selections? next-template-path)))
                    (if select-fn
                      (select-fn new-option-path)))
                  (.stopPropagation e))}
     [:div.flex.align-items-c
      [:div.flex-grow-1
       [:div.flex
        [:span.f-w-b.f-s-1.flex-grow-1 name]
        (if help
          [show-info-button expanded? new-option-path])]
       (if (and help expanded?)
         [help-section help])
       (if (and (or selected? (= 1 (count options))) ui-fn)
         (ui-fn new-option-path built-template app-state built-char))
       (if (not meets-prereqs?)
         [:div.i.f-s-12.f-w-n 
          (str "Requires " (s/join ", " failed-prereqs))])]
      (if has-selections?
        [:i.fa.fa-caret-right.m-l-5])]]))

(defn add-option-selector [{:keys [::t/key ::t/name ::t/options ::t/new-item-text ::t/new-item-fn ::t/options] :as selection} entity path built-template]
  [:select.builder-option.builder-option-dropdown.m-t-0
   {:value ""
    :on-change
    (fn [e]
      (let [value (.. e -target -value)]
        (if value
          (let [kw (keyword value)
                value-path (entity/get-entity-path built-template entity path)
                new-item (new-item-fn
                          selection
                          options
                          (get-in @app-state (concat [:character] value-path))
                          kw)]
            (swap! app-state #(update % :character (fn [c] (update-option
                                                            built-template c path
                                                            (fn [options] (conj (vec options) new-item))))))))))}
   [:option.builder-dropdown-item
    {:disabled true
     :value ""}
    "select to add"]
   (doall
    (map
     (fn [{:keys [::t/key ::t/name]}]
       ^{:key key}
       [:option.builder-dropdown-item
        {:value (if key (clojure.core/name key))}
        name])
     options))])

(defn option-item [character built-template option-path all-selections quantity? removeable? parent-selection i {:keys [::t/name ::t/key ::t/help ::t/select-fn ::t/selections] :as option}]
  (let [new-option-path (conj option-path key)
        value-path (entity/get-option-value-path built-template character new-option-path)
        has-selections? (boolean (seq selections))
        expanded? (get-in @app-state [:expanded-paths new-option-path])]
    ^{:key key}
    [:div.flex.align-items-c.m-5.f-w-b.f-s-14
     [:div.flex-grow-1.b-1.b-rad-5.pointer.b-orange.hover-shadow.p-5
      (if has-selections?
        {:on-click
         (fn [e]
           (let [first-selection (first selections)
                 next-selection-path (conj new-option-path (::t/key first-selection))
                 next-selection (assoc first-selection
                                       ::path next-selection-path
                                       ::parent (assoc option ::path new-option-path))
                 next-template-path (entity/get-template-selection-path built-template next-selection-path [])]
             (swap! app-state (fn [as]
                                (-> as
                                    (assoc :stepper-selection-path next-template-path)
                                    (assoc :stepper-selection (assoc next-selection ::path next-selection-path)))))
             (if select-fn
               (select-fn new-option-path)))
           (.stopPropagation e))})
      [:div.flex.align-items-c
       [:span.flex-grow-1.m-l-5.m-t-5.m-b-5 name]
       (if help
         [show-info-button expanded? new-option-path])
       (if quantity?
         [:input.input.w-60.m-t-0.m-l-5
          {:type :number
           :value (get-in character value-path)
           :on-change (fn [e]
                        (let [value (.. e -target -value)
                              int-val (if (not (s/blank? value)) (js/parseInt value))]
                          (swap! app-state assoc-in (concat [:character] value-path) int-val))
                        (.stopPropagation e))}])
       (if has-selections?
         [:i.fa.fa-caret-right.m-r-5])]
      (if expanded? [help-section help])]
     (if removeable? [remove-option-button option-path built-template i])]))

(defn jump-to-link [name path option-path option selection built-template subselections? stepper-selection-path]
  (let [jump-to-handler (fn [e]
                          (let [next-selection (assoc selection
                                                      ::path path
                                                      ::parent option)
                                next-template-path (entity/get-template-selection-path built-template path [])]
                            (swap! app-state (fn [as]
                                               (-> as
                                                   (assoc :stepper-selection-path next-template-path)
                                                   (assoc :stepper-selection next-selection))))))
        path-to-check (or option-path path)
        current? (and (>= (count stepper-selection-path) (count path-to-check))
                      (= path-to-check (subvec stepper-selection-path 0 (count path-to-check))))]
    [:div.p-5
     (if subselections?
       [:span
        {:class-name (if current? "f-w-b")}
        (expand-button (concat [:jump-to] path) name name jump-to-handler)]
       [:span.underline
        {:class-name (if current? "f-w-b")
         :on-click jump-to-handler}
        name])]))

(defn jump-to-component [option-path option-paths character built-template selections parent-option stepper-selection-path]
  [:div.orange
   (doall
    (map
     (fn [selection]
       (let [selection-path (conj option-path (::t/key selection))
             selected-options (filter
                                (fn [o]
                                  (seq (::t/selections o)))
                                (filter-selected selection-path key option-paths (::t/options selection) character built-template))
             subselections? (some (comp seq ::t/selections) selected-options)]
         ^{:key (::t/key selection)}
         [:div.pointer
          {:class-name (if (seq option-path) "p-l-20")}
          (jump-to-link (::t/name selection) selection-path nil parent-option selection built-template subselections? stepper-selection-path)
          (if (and (seq selected-options)
                   (get-in @app-state [:expanded-paths (concat [:jump-to] selection-path)]))
            (doall
             (map
              (fn [option]
                (let [new-option-path (conj selection-path (::t/key option))
                      option-selections (::t/selections option)
                      first-selection (first option-selections)
                      first-selection-path (conj new-option-path (::t/key first-selection))]
                  ^{:key (::t/key option)}
                  [:div.pointer.p-l-20
                   (jump-to-link (::t/name option) first-selection-path new-option-path option first-selection built-template true stepper-selection-path)
                   (if (get-in @app-state [:expanded-paths (concat [:jump-to] first-selection-path)])
                     (jump-to-component
                      new-option-path
                      option-paths
                      character
                      built-template
                      (::t/selections option)
                      option
                      stepper-selection-path))]))
              selected-options)))]))
     selections))])

(defn options-column [character built-char built-template option-paths collapsed-paths stepper-selection-path stepper-selection plugins]
  (let [all-selections (get-all-selections [] built-template option-paths built-char)
        {:keys [::t/name ::t/options ::t/help ::t/min ::t/max ::t/sequential? ::t/quantity? ::parent ::path] :as selection}
        (if stepper-selection
          stepper-selection
          (first all-selections))
        option-path path
        expanded? (get-in @app-state [:expanded-paths option-path])
        additive? (and (not (::t/multiselect? selection)) (or (> max min) (nil? max)))
        qty-change-fn (partial make-quantity-change-fn path key built-template character app-state)
        multiselect? (or (and (> min 1) (= min max)) (::t/multiselect? selection))
        selection-option-path (entity/get-entity-path built-template character option-path)
        selection-option (get-in character selection-option-path)
        remaining (if max (- max (count selection-option)))
        disable-select-new? (and multiselect? (not (nil? max)) (zero? remaining))
        parent-path (::path parent)
        ancestor-paths (reductions conj [] path)
        ancestors (map (fn [a-p] (get-in built-template (entity/get-template-selection-path built-template a-p [])))
                       (take-nth 2 (butlast ancestor-paths)))
        ancestor-names (map ::t/name (remove nil? ancestors))
        top-level-name (some (fn [s]
                               (if (= (first option-path)
                                      (::t/key s))
                                 (::t/name s)))
                             (::t/selections built-template))]
    [:div.w-100-p
     [:div#options-column.b-1.b-rad-5
      [:div.flex.justify-cont-end
       [:span.p-5 (expand-button [:jump-to] "jump to..." "jump to...")]
       #_[:select.f-s-12.p-2.m-t-0.m-l-5.white.no-border.bg-trans
        {:value (first option-path)
         :on-change (fn [e]
                      (let [value (keyword (.. e -target -value))
                            selection (some (fn [s]
                                              (if (= value (::t/key s))
                                                (assoc s ::path [value])))
                                            (::t/selections built-template))]
                        (set-next! nil selection (entity/get-template-selection-path built-template [value] []))))}
        (doall
         (map
          (fn [{:keys [::t/name ::t/key]}]
            ^{:key key}
            [:option.builder-dropdown-item.f-s-14
             {:value (clojure.core/name key)}
             name])
          (::t/selections built-template)))]]
      (if (get-in @app-state [:expanded-paths [:jump-to]])
        [:div.p-10
         (jump-to-component [] option-paths character built-template (::t/selections built-template) nil (to-option-path stepper-selection-path built-template))])
      [:div.flex.justify-cont-s-b.p-t-5.p-10.align-items-t
       [:button.form-button.p-5-10.m-r-5
        {:on-click
         (fn [_] (set-prev-selection!
                  built-template
                  option-paths
                  character
                  stepper-selection-path
                  all-selections
                  built-char))} "Back"]
       [:div.flex-grow-1
        (if parent [:h4.f-s-14.t-a-c.m-b-5 (str (s/join " - " ancestor-names))])
        [:h3.f-w-b.f-s-20.t-a-c name]]
       [:button.form-button.p-5-10.m-l-5
        {:on-click
         (fn [_] (set-next-selection!
                  built-template
                  option-paths
                  character
                  stepper-selection-path
                  all-selections
                  built-char))}
        "Next"]]
      [:div.p-l-10.p-r-10
       [:div.flex
        (if (> (count options) 1)
          [:span.i.flex-grow-1 (cond
                                 (= min max) (str "Select "
                                                  (if multiselect?
                                                    (str remaining " more")
                                                    min))
                                 (and (= min 1)
                                      (> max 1)) (str "Select up to " max)
                                 (and (> min 1)
                                      (> max min)) (str "Select between " min " and " max)
                                 (and (nil? max)) "Select to add")])
        (if help
          [show-info-button expanded? option-path])]
       (if expanded? [help-section help])]
      [:div.p-5
       (if additive?
         [:div
          (if (not sequential?)
            [:div.m-5
             [add-option-selector selection character option-path built-template]])
          [:div
           (doall
            (let [filtered (filter-selected option-path key option-paths options character built-template)]
              (map-indexed
               (fn [i option]
                 (option-item character built-template option-path all-selections quantity? (or (pos? i)
                                                                                                (> (count filtered) 1)
                                                                                                (zero? min)) selection i option))
               filtered)))]
          (if sequential?
            [add-option-button selection character option-path built-template])]
         [:div
          (doall
           (map
            (fn [option]
              (option-selector character built-char built-template option-paths stepper-selection-path option-path selection disable-select-new? option))
            options))])]]]))

(defn get-event-value [e]
  (.-value (.-target e)))

(defn character-field [app-state prop-name type & [cls-str]]
  (let [path [::entity/values prop-name]]
    [type {:class-name (str "input " cls-str)
           :type :text
           :value (get-in @app-state (concat [:character] path))
           :on-change (fn [e]
                        (swap! app-state
                               assoc-in
                               (concat [:character] path)
                               (get-event-value e)))}]))

(defn character-input [app-state prop-name & [cls-str]]
  (character-field app-state prop-name :input cls-str))

(defn character-textarea [app-state prop-name & [cls-str]]
  (character-field app-state prop-name :textarea cls-str))

(defn builder-columns [built-template built-char option-paths collapsed-paths stepper-selection-path stepper-selection plugins active-tabs stepper-dismissed?]
  [:div.flex-grow-1.flex
   {:class-name (s/join " " (map #(str (name %) "-tab-active") active-tabs))}
   #_[:div.builder-column.stepper-column
    (if (not stepper-dismissed?)
      [selection-stepper
       built-template
       option-paths
       @character-ref
       stepper-selection-path])]
   [:div.builder-column.options-column
    [options-column (:character @app-state) built-char built-template option-paths collapsed-paths stepper-selection-path stepper-selection plugins]]
   [:div.flex-grow-1.builder-column.personality-column
    [:div.m-t-5
     [:span.personality-label.f-s-18 "Character Name"]
     [character-input app-state :character-name]]
    [:div.field
     [:span.personality-label.f-s-18 "Personality Trait 1"]
     [character-textarea app-state :personality-trait-1]]
    [:div.field
     [:span.personality-label.f-s-18 "Personality Trait 2"]
     [character-textarea app-state :personality-trait-2]]
    [:div.field
     [:span.personality-label.f-s-18 "Ideals"]
     [character-textarea app-state :ideals]]
    [:div.field
     [:span.personality-label.f-s-18 "Bonds"]
     [character-textarea app-state :bonds]]
    [:div.field
     [:span.personality-label.f-s-18 "Flaws"]
     [character-textarea app-state :flaws]]
    [:div.field
     [:span.personality-label.f-s-18 "Image URL"]
     [character-input app-state :image-url]]
    [:div.field
     [:span.personality-label.f-s-18 "Description/Backstory"]
     [character-textarea app-state :description "h-800"]]]
   [:div.builder-column.details-column
    [character-display built-char]]])

(defn builder-tabs [active-tabs]
  [:div.hidden-lg.w-100-p
   [:div.builder-tabs
    [:span.builder-tab.options-tab
     {:class-name (if (active-tabs :options) "selected-builder-tab")
      :on-click (fn [_] (swap! app-state assoc-in tab-path #{:build :options}))} "Options"]
    [:span.builder-tab.personality-tab
     {:class-name (if (active-tabs :personality) "selected-builder-tab")
      :on-click (fn [_] (swap! app-state assoc-in tab-path #{:build :personality}))} "Description"]
    [:span.builder-tab.build-tab
     {:class-name (if (active-tabs :build) "selected-builder-tab")
      :on-click (fn [_] (swap! app-state assoc-in tab-path #{:build :options}))} "Build"]
    [:span.builder-tab.details-tab
     {:class-name (if (active-tabs :details) "selected-builder-tab")
      :on-click (fn [_] (swap! app-state assoc-in tab-path #{:details}))} "Details"]]])

(defn export-pdf [built-char]
  (fn [_]
    (let [field (.getElementById js/document "fields-input")]
      (aset field "value" (str (pdf-spec/make-spec built-char)))
      (.submit (.getElementById js/document "download-form")))))

(defn download-form [built-char]
  [:form.download-form
   {:id "download-form"
    :action (if (.startsWith js/window.location.href "http://localhost")
              "http://localhost:8890/character.pdf"
              "/character.pdf")
    :method "POST"
    :target "_blank"}
   [:input {:type "hidden" :name "body" :id "fields-input"}]])

(defn header [built-char]
  [:div.flex.align-items-c.justify-cont-s-b.w-100-p
   [:h1.f-s-36.f-w-b.m-t-21.m-b-19.m-l-10 "Character Builder"]
   [:div
    #_[:button.form-button.h-40.opacity-5
     {:on-click (export-pdf built-char)}
     [:span "Save"]
     [:span.m-l-5 "(coming soon)"]]
    [:button.form-button.h-40.m-l-5
     {:on-click (export-pdf built-char)}
     [:span "Print"]]]])

(defn character-builder []
  ;;(cljs.pprint/pprint (:character @app-state))
  ;;(js/console.log "APP STATE" @app-state)
  (let [selected-plugin-options (into #{}
                                      (map ::entity/key)
                                      (get-in @app-state [:character ::entity/options :optional-content]))
        selected-plugins (map
                          :selections
                          (filter
                           (fn [{:keys [key]}]
                             (selected-plugin-options key))
                           plugins))
        merged-template (if (seq selected-plugins)
                          (update template
                                  ::t/selections
                                  (fn [s]
                                    (apply
                                     entity/merge-multiple-selections
                                     s
                                     selected-plugins)))
                          template)
        option-paths (make-path-map (:character @app-state))
        built-template (entity/build-template (:character @app-state) merged-template)
        built-char (entity/build (:character @app-state) built-template)
        active-tab (get-in @app-state tab-path)
        view-width (.-width (gdom/getViewportSize js/window))
        stepper-selection-path (:stepper-selection-path @app-state)
        stepper-selection (:stepper-selection @app-state)
        collapsed-paths (:collapsed-paths @app-state)
        mouseover-option (:mouseover-option @app-state)
        plugins (:plugins @app-state)
        stepper-dismissed? (:stepper-dismissed @app-state)]
    ;(js/console.log "BUILT TEMPLAT" built-template)
    ;(print-char built-char)
    [:div.app
     {:on-scroll (fn [e]
                   (let [app-header (js/document.getElementById "app-header")
                         header-height (.-offsetHeight app-header)
                         scroll-top (.-scrollTop (.-target e))
                         sticky-header (js/document.getElementById "sticky-header")
                         app-main (js/document.getElementById "app-main")
                         scrollbar-width (- js/window.innerWidth (.-offsetWidth app-main))
                         header-container (js/document.getElementById "header-container")]
                     (set! (.-paddingRight (.-style header-container)) (str scrollbar-width "px"))
                     (if (>= scroll-top header-height)
                       (set! (.-display (.-style sticky-header)) "block")
                       (set! (.-display (.-style sticky-header)) "none"))))}
     [download-form built-char]
     [:div#app-header.app-header
      [:div.app-header-bar.container
       [:div.content
        [:img.orcpub-logo {:src "image/orcpub-logo.svg"}]]]]
     [:div#sticky-header.sticky-header.w-100-p.posn-fixed
      [:div.flex.justify-cont-c.bg-light
       [:div#header-container.f-s-14.white.content
        (header built-char)]]]
     [:div.flex.justify-cont-c.white
      [:div.content (header built-char)]]
     [:div.flex.justify-cont-c.white
      [:div.content [builder-tabs active-tab]]]
     [:div#app-main.flex.justify-cont-c.p-b-40
      [:div.f-s-14.white.content
       [:div.flex.w-100-p
        [builder-columns
         built-template
         built-char
         option-paths
         collapsed-paths
         stepper-selection-path
         stepper-selection
         plugins
         active-tab
         stepper-dismissed?]]]]
     [:div.white.flex.justify-cont-c
      [:div.content.f-w-n.f-s-14
       [:div.p-10
        [:div.m-b-5 "Icons made by Lorc and Caduceus. Available on " [:a.orange {:href "http://game-icons.net"} "http://game-icons.net"]]]]]]))