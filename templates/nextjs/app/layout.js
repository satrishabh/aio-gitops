export const metadata = { title: 'My App', description: 'Next.js starter deployed by the AIO platform' }

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body style={{ margin: 0, fontFamily: 'Inter, system-ui, sans-serif', background: '#0b0f17', color: '#e6eaf2' }}>
        {children}
      </body>
    </html>
  )
}
