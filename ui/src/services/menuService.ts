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

import { rest, v2 } from './axiosInstances'
import { MainMenu, NotificationSummary } from '@/types/mainMenu'
import { loadDefaultPreferences, loadPreferences, savePreferences } from '@/services/localStorageService'

const menuEndpoint = 'menu'
const notificationSummaryEndpoint = 'notifications/summary'
// null if uninitialized
const isSideMenuExpanded = ref<boolean | null>(null)

const getMainMenu = async (): Promise<MainMenu | false> => {
  try {
    const resp = await v2.get(menuEndpoint)
    return resp.data
  } catch (err) {
    return false
  }
}

const getNotificationSummary = async (): Promise<NotificationSummary | false> => {
  try {
    const resp = await rest.get(notificationSummaryEndpoint)
    return resp.data
  } catch (err) {
    return false
  }
}

const loadIsSideMenuExpanded = () => {
  const prefs = loadPreferences()
  isSideMenuExpanded.value = prefs?.isSideMenuExpanded ?? false
}

const getIsSideMenuExpanded = () => {
  if (isSideMenuExpanded.value === null) {
    loadIsSideMenuExpanded()
  }

  return isSideMenuExpanded.value
}

const setIsSideMenuExpanded = (val: boolean) => {
  isSideMenuExpanded.value = val

  const prefs = loadPreferences() || loadDefaultPreferences()
  prefs.isSideMenuExpanded = val

  savePreferences(prefs)
}

export {
  getIsSideMenuExpanded,
  getMainMenu,
  getNotificationSummary,
  loadIsSideMenuExpanded,
  setIsSideMenuExpanded
}
