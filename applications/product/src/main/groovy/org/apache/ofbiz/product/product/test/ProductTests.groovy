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

class ProductTests extends OFBizTestCase {

    ProductTests(String name) {
        super(name)
    }

    void testUpdateProductCategory() {
        Map serviceCtx = [:]
        serviceCtx.categoryName = 'Updated Test Product Category'
        serviceCtx.longDescription = 'Updated Long Test Product Category Description'
        serviceCtx.productCategoryId = 'CATALOG1_BEST_SELL'
        serviceCtx.productCategoryTypeId = 'BEST_SELL_CATEGORY'
        serviceCtx.userLogin = userLogin
        Map serviceResult = dispatcher.runSync('updateProductCategory', serviceCtx)
        assert ServiceUtil.isSuccess(serviceResult)

        GenericValue prodCategory = from('ProductCategory').where('productCategoryId', 'CATALOG1_BEST_SELL').queryOne()
        if (prodCategory) { // fails in framework integration tests only, data is in ecommerce
            assert prodCategory.categoryName == 'Updated Test Product Category'
            assert prodCategory.productCategoryTypeId == 'BEST_SELL_CATEGORY'
        }
    }

}
