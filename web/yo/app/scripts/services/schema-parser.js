'use strict';

angular.module('dmpApp').
  /**
   * A service that parses json-schema into an internal tree model [1]. It also
   *   provides functionality to transform an XML2JSON model [2] into the
   *   aforementioned tree model, using the same json-schema definition.
   *
   *   [1] The internal tree model is defined in directives/tree.js
   *   [2] Based on the unmodified results of https://github.com/hay/xml2json
   *
   *   Due to the nature of representing a tree structure, most of these
   *   methods are utilizing either plain recursion or trampoline recursion.
   *
   *   Normally only mapData and getData would be needed to be used.
   *
   * * mapData returns the parsed data from the schema
   * * getData returns all title data. most useful in combination
   *           with with the editableTitle flag
   */
  factory('schemaParser', function () {
    /**
     * Maps from json-schema to the internal tree model.  Since json-schema
     *   already is very tree-ish, there is nothing much to do but renaming
     *   some properties and apply recursion all the way down.
     *
     * @param name {String}  The name of the current property, that is
     *   enumerated on.
     * @param container {Object}  The definition of the current property.
     *   A json-schema is usually like "Property": { ... (definition) }
     *   and `name' and `container' are just that.
     * @param editableTitle {Boolean}
     * @returns {{name: String, show: boolean}}
     */
    function mapData(name, container, editableTitle) {
      var data = {'name': name, 'show': true, 'editableTitle': editableTitle};
      if (container['properties']) {
        var children = [];
        angular.forEach(container['properties'], function (val, key) {
          children.push(mapData(key, val, editableTitle));
        });
        data['children'] = children;
      }

      data.hasChildren = (data['children'] && data['children'].length > 0);

      return data;
    }

    /**
     * creates a leaf item
     * @param name {String}
     * @param children {Object}
     * @param title {String=} (optional)
     * @param extra {Object=} (optional)
     * @returns {*}
     */
    function makeItem(name, children, title, extra) {
      var item = {'name': name, 'show': true};
      if (children && children.length) {
        item['children'] = children;
      }
      if (title) {
        item['title'] = title;
      }
      return angular.extend(extra || {}, item);
    }

    /**
     * creates leafs from object type
     * @param container {*}
     * @param name {String}
     * @param properties {Object}
     * @returns {*}
     */
    function parseObject(container, name, properties) {
      var ary = [];
      angular.forEach(properties, function (val, key) {
        if (container[key]) {
          var it = parseAny(container[key], key, val);
          if (it) {
            ary.push(it);
          }
        }
      });
      return makeItem(name, ary);
    }

    /**
     * creates leafs from array type
     * @param container {*}
     * @param name {String}
     * @param properties {Array}
     * @returns {*}
     */
    function parseArray(container, name, properties) {
      var ary = [];
      angular.forEach(container, function (item) {
        var it = parseAny(item, name, properties);
        if (it) {
          ary.push(it);
        }
      });
      return makeItem(name, ary);
    }

    /**
     *  creates leafs from string type
     * @param container {*}
     * @param name {String}
     * @returns {*}
     */
    function parseString(container, name) {
      if (angular.isString(container)) {
        return makeItem(name, null, container.trim(), {leaf: true});
      }

      if (container['#text'] && container['#text'].trim()) {
        return makeItem(name, null, container['#text'].trim(), {leaf: true});
      }

      if (angular.isArray(container)) {
        var ary = [];
        angular.forEach(container, function (item) {
          var it = parseString(item, name);
          if (it) {
            ary.push(it);
          }
        });
        return makeItem(name, ary);
      }
    }

    /**
     * creates leafs from enum type
     * @param container {*}
     * @param name {String}
     * @param enumeration {Enum}
     * @returns {*}
     */
    function parseEnum(container, name, enumeration) {
      if (enumeration.indexOf(container) !== -1) {
        return makeItem(name, null, container);
      }
    }

    /**
     * dispatches current leaf to correct parser
     * @param container {*}
     * @param name {String}
     * @param obj {*}
     * @returns {*}
     */
    function parseAny(container, name, obj) {
      if (obj['type'] === 'object') {
        return parseObject(container, name, obj['properties']);
      }
      if (obj['type'] === 'array') {
        return parseArray(container, name, obj['items']);
      }
      if (obj['type'] === 'string') {
        return parseString(container, name);
      }
      if (obj['enum']) {
        return parseEnum(container, name, obj['enum']);
      }
    }

    /**
     * takes input data object and returns title data as array
     * @param data {*}
     * @returns {Array}
     */
    function getData(data) {
      if(data.children) {
        var returnData = [];
        angular.forEach(data.children,function(child) {
          var tempData = getData(child);
          if(tempData.length > 0) {
            returnData.push(tempData);
          }
        });
        return returnData;
      } else {
        return (data.title) ? data.title : '';
      }
    }

    return {
      mapData: mapData
    , makeItem: makeItem
    , parseObject: parseObject
    , parseArray: parseArray
    , parseString: parseString
    , parseEnum: parseEnum
    , parseAny: parseAny
    , getData: getData
    };
  });