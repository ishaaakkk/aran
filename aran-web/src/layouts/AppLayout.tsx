import React from 'react';
import { IconRail } from '../components/IconRail';
import { TerminalTopBar } from '../components/TerminalTopBar';
import { TutorialManager } from '../components/TutorialManager';

interface AppLayoutProps {
  children: React.ReactNode;
}

export const AppLayout: React.FC<AppLayoutProps> = ({ children }) => {
  return (
    <div data-testid="app-shell" className="flex w-screen h-screen bg-background text-foreground font-sans overflow-hidden">
      <TutorialManager />
      <IconRail />
      <div className="flex-grow flex flex-col min-w-0 relative overflow-hidden">
        <TerminalTopBar />
        <main className="flex-grow overflow-auto flex flex-col min-h-0">
          {children}
        </main>
      </div>
    </div>
  );
};

export default AppLayout;
