<template>
  <div class="footer">
    <span>
      <a href="about/index.jsp">BlueBirdOps</a>
      {{ 'v' + mainMenu.version || '--' }}
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import { useMenuStore } from '@/stores/menuStore'
import { MainMenu } from '@/types/mainMenu'

const menuStore = useMenuStore()

const mainMenu = computed<MainMenu>(() => menuStore.mainMenu)

</script>

<style lang="scss">
// Shared footer band height, mirroring --onms-header-height in Menubar.vue. Pages
// that size themselves against the height the app shell leaves them subtract this
// (Map, Logs, SCV), so it lives here, next to the element it describes, instead of
// being repeated as a literal in each of them.
//
// It is the band this component actually draws: one 12px line of text at the normal
// line height (about 0.9rem), 0.25rem of padding above and below, and the 1px top
// border. `.footer` takes its min-height from the token, so the two cannot drift
// apart. Note it is a *min*: at very narrow widths the version line wraps and the
// band grows past the token, which is the one case those page calculations still
// under-count.
:root {
  --onms-footer-height: calc(0.9rem + 0.5rem + 1px);
}
</style>

<style lang="scss" scoped>
.footer {
  display: block;
  font-size: 12px;
  text-align: right;
  min-height: var(--onms-footer-height);
  margin-left: -15px;
  margin-right: -15px;
  padding: 0.25rem 0.42rem;
  background-color: #e9ecef;
  border-top: 1px solid rgba(0, 0, 0, .125);
}
</style>
