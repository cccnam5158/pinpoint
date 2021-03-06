(function() {
	'use strict';
	
	pinpointApp.factory('filteredMapUtilService', [ 'filterConfig', 'encodeURIComponentFilter', 'ServerMapFilterVoService',
	    function (cfg, encodeURIComponentFilter, ServerMapFilterVoService) {
	        // define private variables
	        var self;
	
	        return {
	
	            /**
	             * merge filters
	             * @param oNavbarVoService
	             * @param oServerMapFilterVoService
	             * @returns {Array}
	             */
	            mergeFilters: function (oNavbarVoService, oServerMapFilterVoService) {
	                var newFilter = [];
	                if (oNavbarVoService.getFilter()) {
	                    var prevFilter = JSON.parse(oNavbarVoService.getFilter());
	                    if (angular.isArray(prevFilter)) {
	                        newFilter = prevFilter;
	
	                        var result = this.findFilterInNavbarVo(
	                            oServerMapFilterVoService.getFromApplication(),
	                            oServerMapFilterVoService.getFromServiceType(),
	                            oServerMapFilterVoService.getToApplication(),
	                            oServerMapFilterVoService.getToServiceType(),
	                            oNavbarVoService
	                        );
	                        if (result) {
	                            newFilter[result.index] = oServerMapFilterVoService.toJson();
	                            return newFilter;
	                        }
	                    }
	                }
	                newFilter.push(oServerMapFilterVoService.toJson());
	                return newFilter;
	            },
	
	            /**
	             * merge hints
	             * @param oNavbarVoService
	             * @param oServerMapHintVoService
	             * @returns {{}}
	             */
	            mergeHints: function (oNavbarVoService, oServerMapHintVoService) {
	                var newHint = {},
	                    prevHint = this.parseShortHintToLongHint(JSON.parse(oNavbarVoService.getHint())),
	                    nowHint = oServerMapHintVoService.getHint();
	                if (prevHint) {
	                    if (nowHint) {
	                        var nowHintKey = _.keys(nowHint)[0],
	                            nowHintValue = nowHint[nowHintKey];
	                        newHint = angular.copy(prevHint);
	
	                        if (angular.isDefined(newHint[nowHintKey])) {
	                            newHint[nowHintKey] = _.union(newHint[nowHintKey], nowHintValue);
	                            newHint[nowHintKey] = this.uniqueHintValue(newHint[nowHintKey]);
	                        } else {
	                            newHint[nowHintKey] = nowHintValue;
	                        }
	                    } else {
	                        newHint = prevHint;
	                    }
	                } else {
	                    newHint = oServerMapHintVoService.getHint();
	                }
	                return newHint;
	            },
	
	            /**
	             * unique hint value
	             * @param hintValue
	             * @returns {array}
	             */
	            uniqueHintValue: function (hintValue) {
	                for (var i=0; i<hintValue.length; ++i) {
	                    for (var j=i+1; j<hintValue.length; ++j) {
	                        if (hintValue[i]['rpc'] === hintValue[j]['rpc'] &&
	                            hintValue[i]['rpcServiceTypeCode'] === hintValue[j]['rpcServiceTypeCode']) {
	                            hintValue.splice(j--, 1);
	                        }
	                    }
	                }
	                return hintValue;
	            },
	
	            /**
	             * parse short hint to long hint
	             * @param shortHint
	             * @returns {*}
	             */
	            parseShortHintToLongHint: function (shortHint) {
	                var newHint = angular.copy(shortHint);
	                angular.forEach(newHint, function (val, key) {
	                    var hintData = [];
	                    for(var i = 0; i<val.length; i+=2) {
	                        hintData.push({
	                            rpc: val[i],
	                            rpcServiceTypeCode: val[i+1]
	                        });
	                    }
	                    newHint[key] = hintData;
	                });
	                return newHint;
	            },
	
	            /**
	             * parse long hint to short hint
	             * @param longHint
	             * @returns {*}
	             */
	            parseLongHintToShortHint: function (longHint) {
	                var newHint = angular.copy(longHint);
	                angular.forEach(newHint, function (val, key) {
	                    var hintData = [];
	                    for(var k in val) {
	                        hintData.push(val[k]['rpc']);
	                        hintData.push(val[k]['rpcServiceTypeCode']);
	                    }
	                    newHint[key] = hintData;
	                });
	                return newHint;
	            },
	
	            /**
	             * get start value for filter by label
	             * @param label
	             * @param values
	             * @returns {number}
	             */
	            getStartValueForFilterByLabel: function (label, values) {
	                var labelKey = (function () {
	                        for (var key in values) {
	                            if (values[key].label === label) {
	                                return key;
	                            }
	                        }
	                        return false;
	                    })(),
	                    startValue = 0;
	
	                if (labelKey > 0) {
	                    startValue = parseInt(values[labelKey - 1].label, 10);
	                }
	                return startValue;
	            },
	
	            /**
	             * get filtered map url with filter vo
	             * @param oNavbarVoService
	             * @param oServerMapFilterVoService
	             * @param oServerMapHintVoService
	             * @returns {string}
	             */
	            getFilteredMapUrlWithFilterVo: function (oNavbarVoService, oServerMapFilterVoService, oServerMapHintVoService) {
	                var newFilter = this.mergeFilters(oNavbarVoService, oServerMapFilterVoService),
	                    mainApplication = oServerMapFilterVoService.getMainApplication() + '@' + oServerMapFilterVoService.getMainServiceTypeName(),
	                    url = '#/filteredMap/' + mainApplication + '/' + oNavbarVoService.getReadablePeriod() + '/' +
	                        oNavbarVoService.getQueryEndDateTime() + '/' + encodeURIComponentFilter(JSON.stringify(newFilter));
	                if (oNavbarVoService.getHint() || oServerMapHintVoService.getHint()) {
	                    var newLongHint = this.mergeHints(oNavbarVoService, oServerMapHintVoService),
	                        newShortHint = this.parseLongHintToShortHint(newLongHint);
	                    url += '/' + encodeURIComponentFilter(JSON.stringify(newShortHint));
	                }
	                return url;
	            },
	
	            /**
	             * find filter in navbar vo
	             * @param fa
	             * @param fst
	             * @param ta
	             * @param tst
	             * @param oNavbarVoService
	             * @returns {boolean}
	             */
	            findFilterInNavbarVo: function (fa, fst, ta, tst, oNavbarVoService) {
	                var filters = JSON.parse(oNavbarVoService.getFilter()),
	                    result = false;
	                if (fst === 'USER') {
	                    fa = 'USER';
	                }
	                if (angular.isArray(filters)) {
	                    angular.forEach(filters, function(filter, index) {
	                        var oServerMapFilterVoService = new ServerMapFilterVo(filter);
	                        if (fa === oServerMapFilterVoService.getFromApplication() &&
	                            ta === oServerMapFilterVoService.getToApplication() &&
	                            fst === oServerMapFilterVoService.getFromServiceType() &&
	                            tst === oServerMapFilterVoService.getToServiceType()) {
	                            result = {
	                                oServerMapFilterVoService : oServerMapFilterVoService,
	                                index: index
	                            };
	                        }
	                    });
	                }
	                return result;
	            },
	
	            /**
	             * do filters have unknown node
	             * @param filters
	             * @returns {boolean}
	             */
	            doFiltersHaveUnknownNode: function (filters) {
	                for (var k in filters) {
	                    if (filters[k].tst === 'UNKNOWN') return true;
	                }
	                return false;
	            }
	        }
	    }
	]);
})();