import { defineConfig } from "vitepress";

export default defineConfig({
  base: process.env.VITEPRESS_BASE || "/",
  title: "Dis2FA",
  description: "Discord-based authentication plugin for offline-mode Minecraft servers.",
  themeConfig: {
    nav: [
      { text: "Guide", link: "/guide/setup" },
      { text: "Configuration", link: "/guide/configuration" },
      { text: "Commands", link: "/guide/commands" },
      { text: "Web Editor", link: "/guide/web-editor" }
    ],
    sidebar: {
      "/guide/": [
        {
          text: "Getting Started",
          items: [
            { text: "Setup", link: "/guide/setup" },
            { text: "Linking and Approvals", link: "/guide/linking" }
          ]
        },
        {
          text: "Operations",
          items: [
            { text: "Commands and Permissions", link: "/guide/commands" },
            { text: "Configuration Reference", link: "/guide/configuration" },
            { text: "Web Config Editor", link: "/guide/web-editor" },
            { text: "Chat Bridge", link: "/guide/chat-bridge" },
            { text: "Role Gating and Ban Sync", link: "/guide/role-ban-sync" },
            { text: "Troubleshooting", link: "/guide/troubleshooting" }
          ]
        }
      ]
    },
    search: {
      provider: "local"
    }
  },
  outline: [2, 3]
});
