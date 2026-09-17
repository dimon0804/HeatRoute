/// <reference types="vite/client" />

/** Переменные сборки, задаваемые Vite. */
interface ImportMetaEnv {
  /** Базовый адрес API; в собранном виде подставляется при сборке образа. */
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
