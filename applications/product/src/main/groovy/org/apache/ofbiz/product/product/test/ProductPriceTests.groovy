/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.product.product.test

import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.service.ServiceUtil
import org.apache.ofbiz.service.testtools.OFBizTestCase

class ProductPriceTests extends OFBizTestCase {

    ProductPriceTests(String name) {
        super(name)
    }

    void testCalculateProductPrice() {
        String productId = 'GZ-2002'
        GenericValue product = from('Product').where('productId', productId).queryOne()
        Map serviceCtx = [:]
        serviceCtx.product = product
        Map resultMap = dispatcher.runSync('calculateProductPrice', serviceCtx)
        assert ServiceUtil.isSuccess(resultMap)
        assert resultMap.defaultPrice == 47.99
        assert resultMap.listPrice == 48
    }

    void testCalculateProductPriceOfVariantProduct() {
        // If product is a variant and no price is set, then default price of virtual product will be set
        String productId = 'GZ-1006-3'
        GenericValue product = from('Product').where('productId', productId).queryOne()
        Map serviceCtx = [:]
        serviceCtx.product = product
        Map resultMap = dispatcher.runSync('calculateProductPrice', serviceCtx)
        assert ServiceUtil.isSuccess(resultMap)
        assert resultMap.defaultPrice == 1.99
        assert resultMap.listPrice == 5.99
    }

    void testCalculateProductPriceOfVirtualProduct() {
        // If product is a virtual and no price is set then then the service return price of a variant product which have lowest DEFAULT_PRICE.
        // It is also considered whether the product is discontinued for sale before using the lowest price against a variant for a virtual product
        String productId = 'DemoProduct'
        GenericValue product = from('Product').where('productId', productId).queryOne()
        Map serviceCtx = [:]
        serviceCtx.product = product
        Map resultMap = dispatcher.runSync('calculateProductPrice', serviceCtx)
        assert ServiceUtil.isSuccess(resultMap)
        assert resultMap.defaultPrice == 10
    }

}
