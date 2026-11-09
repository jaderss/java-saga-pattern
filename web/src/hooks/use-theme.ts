import { useCallback, useState } from 'react'

export type Theme = 'light' | 'dark'

const KEY = 'saga-lab-theme'

function stored(): Theme | null {
  try {
    const value = localStorage.getItem(KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

export function initialTheme(): Theme {
  return stored() ?? (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')
}

export function applyTheme(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

export function useTheme() {
  const [theme, setTheme] = useState<Theme>(initialTheme)

  const toggle = useCallback(() => {
    setTheme((current) => {
      const next = current === 'dark' ? 'light' : 'dark'
      applyTheme(next)
      try {
        localStorage.setItem(KEY, next)
      } catch {
        return next
      }
      return next
    })
  }, [])

  return { theme, toggle }
}
