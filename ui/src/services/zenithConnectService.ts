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

import { rest } from './axiosInstances'
import { ZenithConnectRegistration, ZenithConnectRegistrations } from '@/types/zenithConnect'

const baseEndpoint = '/zenith-connect'

const addZenithRegistration = async (request: ZenithConnectRegistration): Promise<ZenithConnectRegistration | false> => {
  const endpoint = `${baseEndpoint}/registrations`

  try {
    const resp = await rest.post(endpoint, request)

    if (resp.status === 200 || resp.status === 201) {
      return resp.data as ZenithConnectRegistration
    }
  } catch (err) {
    return false
  }

  return false
}

const getZenithRegistrations = async (): Promise<ZenithConnectRegistrations | false> => {
  const endpoint = `${baseEndpoint}/registrations`

  try {
    const resp = await rest.get(endpoint)

    if (resp.status === 200) {
      return resp.data as ZenithConnectRegistrations
    }
  } catch (err) {
    return false
  }

  return false
}

export {
  addZenithRegistration,
  getZenithRegistrations
}
