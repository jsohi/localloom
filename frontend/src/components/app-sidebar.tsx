'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { BookOpenIcon, LayoutDashboardIcon, MessageSquareIcon, SettingsIcon } from 'lucide-react';

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarTrigger,
} from '@/components/ui/sidebar';
import { ThemeToggle } from '@/components/theme-toggle';

const navItems = [
  { title: 'Dashboard', href: '/', icon: LayoutDashboardIcon },
  { title: 'Library', href: '/library', icon: BookOpenIcon },
  { title: 'Chat', href: '/chat', icon: MessageSquareIcon },
  { title: 'Settings', href: '/settings', icon: SettingsIcon },
];

export function AppSidebar() {
  const pathname = usePathname();

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" asChild>
              <Link href="/">
                <div className="bg-primary text-primary-foreground flex aspect-square size-8 items-center justify-center rounded-lg text-sm font-bold">
                  LL
                </div>
                <div className="flex flex-col gap-0.5 leading-none">
                  <span className="font-semibold">LocalLoom</span>
                  <span className="text-muted-foreground text-xs">Knowledge Base</span>
                </div>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupLabel>Navigation</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {navItems.map((item) => (
                <SidebarMenuItem key={item.href}>
                  <SidebarMenuButton asChild isActive={pathname === item.href}>
                    <Link href={item.href}>
                      <item.icon className="size-4" />
                      <span>{item.title}</span>
                    </Link>
                  </SidebarMenuButton>
                </SidebarMenuItem>
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>

      <SidebarFooter>
        <div className="flex items-center justify-between px-2">
          <ThemeToggle />
          <SidebarTrigger />
        </div>
      </SidebarFooter>
    </Sidebar>
  );
}
