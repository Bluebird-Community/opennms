///
/// Licensed to The OpenNMS Group, Inc (TOG) under one or more
/// contributor license agreements.  See the LICENSE.md file
/// distributed with this work for additional information
/// regarding copyright ownership.
///
/// TOG licenses this file to You under the GNU Affero General
/// Public License Version 3 (the "License") or (at your option)
/// any later version.  You may not use this file except in
/// compliance with the License.  You may obtain a copy of the
/// License at:
///
///      https://www.gnu.org/licenses/agpl-3.0.txt
///
/// Unless required by applicable law or agreed to in writing,
/// software distributed under the License is distributed on an
/// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
/// either express or implied.  See the License for the specific
/// language governing permissions and limitations under the
/// License.
///

import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import '@featherds/styles'
import '@featherds/styles/themes/open-light.css'
import '../styles/opennms-feather-styles.scss'
import App from './App.vue'

// id of div to mount this Vue app onto, expected to exist in the embedding web application
const appMountId = import.meta.env.VITE_MENU_APP_MOUNT_ID?.toString() || 'opennms-sidemenu-container'

createApp({
  render: () => h(App)
})
  .use(createPinia())
  .mount(`#${appMountId}`)
